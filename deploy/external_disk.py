#!/usr/bin/env python3
"""外接盘部署入口。仅用标准库；配置检查不创建 VM，也不输出凭据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit


class GuardError(RuntimeError):
    """拒绝不符合磁盘/部署身份约束的操作。"""


def private_path(path: Path) -> None:
    info = path.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise GuardError(f"目录或文件必须归当前用户所有且禁止组/其他用户访问：{path}")


def contained(path: Path, root: Path) -> None:
    if not path.resolve().is_relative_to(root.resolve()):
        raise GuardError(f"路径越过预期根目录：{path}")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check_volume(config: dict, require_space: bool = True) -> None:
    volume = Path(config["volume"])
    try:
        info = plistlib.loads(subprocess.check_output(["diskutil", "info", "-plist", str(volume)]))
    except (subprocess.CalledProcessError, plistlib.InvalidFileException) as exc:
        raise GuardError("无法核对外接卷；禁止创建替代目录") from exc
    if info.get("MountPoint") != str(volume) or not os.path.ismount(volume):
        raise GuardError("预期外接卷未挂载；禁止回退到内置盘")
    if info.get("VolumeUUID", "").upper() != config["volume_uuid"].upper():
        raise GuardError("卷 UUID 不匹配；拒绝使用同名磁盘")
    if info.get("FilesystemType") != "apfs" or info.get("VolumeReadOnly"):
        raise GuardError("需要可写 APFS 卷")
    if not info.get("GlobalPermissionsEnabled"):
        raise GuardError("外接卷尚未启用文件属主管理")
    root = Path(config["runtime_root"])
    contained(root, volume)
    if root == volume or not root.is_dir():
        raise GuardError("部署根目录须预先准备，入口不会自动建立空环境")
    if root.stat().st_dev != volume.stat().st_dev:
        raise GuardError("部署目录不在目标卷上")
    private_path(root)
    free = shutil.disk_usage(volume).free
    if require_space and free < config["min_free_gib"] * 1024**3:
        raise GuardError("外接卷可用空间不足")


class Deployment:
    def __init__(self, repo: Path, config: dict):
        self.repo = repo.resolve()
        self.config = config
        self.root = Path(config["runtime_root"])
        self.workspace = self.root / "workspace"
        self.profile = config["profile"]
        self.context = config.get("docker_context", "colima-" + self.profile)
        self.reuse = config.get("reuse_existing_instance", False)
        if not re.fullmatch(r"[a-z][a-z0-9-]+", self.profile) or self.profile == "default":
            raise GuardError("必须使用独立外接盘实例名")
        if self.profile == "dpdk" and not self.reuse:
            raise GuardError("旧实例名只能用于已经校验的停机副本")
        if not re.fullmatch(r"colima-[a-z][a-z0-9-]+", self.context):
            raise GuardError("Docker context 名称无效")
        if config["compose_project"] == "dpdk-assistant-local" or (config["compose_project"] == "dpdk-assistant" and not self.reuse):
            raise GuardError("禁止使用旧 Compose 项目身份")
        self.env = dict(os.environ)
        for key in ["DOCKER_HOST", "DOCKER_CONTEXT", "LIMA_HOME", "COMPOSE_FILE", "COMPOSE_PROJECT_NAME"]:
            self.env.pop(key, None)
        self.env.update(COLIMA_HOME=str(self.root / "colima"),
                        COLIMA_CACHE_HOME=str(self.root / "cache/colima"))
        # Compose 插值优先读 shell；明确覆盖旧终端残留的主仓库/harness 路径。
        self.env.update(DPDK_REPO_HOST_PATH=str(self.workspace / "dpdk"),
                        TEACHING_HARNESS_PATH=str(self.workspace))

    def guard(self, require_space: bool = True) -> None:
        check_volume(self.config, require_space)
        contained(self.repo, self.workspace)
        for path in [self.root / "colima", self.root / "cache/colima", self.workspace,
                     self.root / "backups", self.root / "migration"]:
            contained(path, self.root)
            private_path(path)
        for path in [self.root / "colima/_lima", self.root / "colima" / self.profile]:
            contained(path, self.root)
        for path in [self.repo / "deploy/.env", self.repo / "deploy/certs/server.key",
                     self.repo / "deploy/external_disk.json"]:
            contained(path, self.root)
            private_path(path)
        for path in [self.repo / "docker-compose.yml", self.repo / "docker-compose.external.yml",
                     self.workspace / "teaching_harness/Dockerfile", self.repo / "deploy/certs/server.crt"]:
            if not path.is_file():
                raise GuardError(f"部署材料不完整：{path}")
        settings = {}
        for line in (self.repo / "deploy/.env").read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                settings[key.strip()] = value.strip().strip("\"'")
        if settings.get("AUTH_MODE") != "account" or settings.get("DATABASE_BACKEND") != "postgres":
            raise GuardError("外接盘全栈必须使用 account + postgres")
        if settings.get("HARNESS_MCP_URL") != "http://harness:8100/mcp":
            raise GuardError("harness 地址必须指向 Compose 内网服务")
        database = urlsplit(settings.get("DATABASE_URL", ""))
        if database.scheme != "postgresql" or database.hostname != "postgres" or database.path != "/assistant":
            raise GuardError("数据库必须指向 Compose postgres 服务中的 assistant 库")
        if settings.get("DPDK_REPO_PATH") != "/data/dpdk":
            raise GuardError("知识源必须使用容器只读路径 /data/dpdk")
        if self.reuse:
            marker = self.root / "migration/relocated-vm.json"
            if not marker.is_file():
                raise GuardError("停机实例副本尚未完成磁盘校验")
            contained(marker, self.root)
            data = json.loads(marker.read_text())
            if not data.get("copy_verified") or data.get("profile") != self.profile or data.get("volume_uuid") != self.config["volume_uuid"]:
                raise GuardError("实例复制记录不属于当前卷或实例")
            source = Path(data["source_colima_home"])
            if source.is_symlink():
                retirement = json.loads((self.root / "migration/native-retirement.json").read_text())
                if (source.resolve() != (self.root / "colima").resolve()
                        or retirement.get("phase") != "aliases-installed"
                        or retirement.get("volume_uuid") != self.config["volume_uuid"]
                        or retirement.get("source_colima_home") != str(source)):
                    raise GuardError("原实例路径链接或退役记录不匹配")
            else:
                original_env = dict(self.env, COLIMA_HOME=str(source))
                result = subprocess.check_output(["colima", "list", "--json"], env=original_env, text=True)
                original = [json.loads(line) for line in result.splitlines() if line.strip()]
                selected = [row for row in original if row.get("name") == self.profile]
                if len(selected) != 1 or selected[0].get("status") != "Stopped":
                    raise GuardError("原实例未确认停止；禁止同时运行同一实例的两份副本")

    def compose(self, *args: str) -> list[str]:
        return ["docker", "--context", self.context, "compose", "--env-file", str(self.repo / "deploy/.env"),
                "-f", str(self.repo / "docker-compose.yml"), "-f", str(self.repo / "docker-compose.external.yml"),
                "-p", self.config["compose_project"], *args]

    def run(self, command: list[str], **kwargs):
        return subprocess.run(command, env=self.env, cwd=self.repo, check=True, **kwargs)

    def daemon_guard(self) -> None:
        expected = "unix://" + str(self.root / "colima" / self.profile / "docker.sock")
        result = self.run(["docker", "context", "inspect", self.context,
                           "--format", "{{(index .Endpoints \"docker\").Host}}"], capture_output=True, text=True)
        if result.stdout.strip() != expected:
            raise GuardError("Docker context 的 socket 不属于目标外接盘实例")
        self.run(["docker", "--context", self.context, "info", "--format", "{{.DockerRootDir}}"],
                 stdout=subprocess.DEVNULL)

    def restored_guard(self) -> None:
        marker = self.root / "migration/restored-and-verified.json"
        if not marker.is_file():
            raise GuardError("B 批次尚未验证数据库恢复；禁止启动空库业务服务")
        contained(marker, self.root)
        data = json.loads(marker.read_text())
        expected = {"phase": "restored-and-verified", "volume_uuid": self.config["volume_uuid"],
                    "compose_project": self.config["compose_project"], "context": self.context,
                    "source": self.config["selected_source"], "release_id": self.config["selected_release_id"],
                    "alembic": self.config.get("target_alembic", "0015_explain_purpose")}
        if any(data.get(key) != value for key, value in expected.items()):
            raise GuardError("恢复验证记录与当前磁盘、数据源或 schema 不一致")

    def images_guard(self) -> None:
        if not self.config.get("preserve_original_images"):
            return
        marker = self.root / "migration/preserved-images.json"
        contained(marker, self.root)
        data = json.loads(marker.read_text())
        if data.get("volume_uuid") != self.config["volume_uuid"] or data.get("compose_project") != self.config["compose_project"]:
            raise GuardError("镜像冻结记录不属于当前卷或 Compose 项目")
        for image, expected in data["images"].items():
            result = self.run(["docker", "--context", self.context, "image", "inspect", image,
                               "--format", "{{.Id}}"], capture_output=True, text=True)
            if result.stdout.strip() != expected:
                raise GuardError("既有应用镜像标签已变化；禁止替换旧代码")
        pg = data["postgres"]
        result = self.run(["docker", "--context", self.context, "inspect", pg["container"],
                           "--format", "{{.Id}} {{.Image}}"], capture_output=True, text=True)
        if result.stdout.strip() != pg["container_id"] + " " + pg["image_id"]:
            raise GuardError("原 PostgreSQL 容器已变化；禁止自动重建或替换")

    def backup(self) -> None:
        folder = self.root / "backups"
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        target = folder / f"assistant-{stamp}.dump"
        partial = target.with_suffix(".partial")
        try:
            with partial.open("xb") as output:
                self.run(self.compose("exec", "-T", "postgres", "pg_dump", "-U", "postgres", "-Fc",
                                      "--exclude-table-data=public.rag_task_inputs", "assistant"), stdout=output)
            with partial.open("rb") as source:
                toc = self.run(self.compose("exec", "-T", "postgres", "pg_restore", "-l"),
                               stdin=source, capture_output=True, text=True).stdout
            if any("TABLE DATA" in line and "rag_task_inputs" in line for line in toc.splitlines()):
                raise GuardError("归档含临时输入数据，拒绝保留")
            partial.rename(target)
            mirror = Path(self.config["emergency_backup_dir"]).expanduser()
            if mirror.resolve().is_relative_to(Path(self.config["volume"]).resolve()):
                raise GuardError("应急副本必须位于独立磁盘")
            mirror.mkdir(parents=True, exist_ok=True, mode=0o700)
            private_path(mirror)
            duplicate = mirror / target.name
            shutil.copy2(target, duplicate)
            digest = file_sha256(target)
            if file_sha256(duplicate) != digest:
                raise GuardError("应急副本校验失败")
            evidence = {"filename": target.name, "sha256": digest,
                        "temporary_input_data_excluded": True, "restore_verified": False}
            for directory in [folder, mirror]:
                (directory / (target.stem + ".json")).write_text(json.dumps(evidence, indent=2) + "\n")
            print(f"备份及独立副本已生成：{target.name}；SHA256={digest}；实际还原尚未验证")
        finally:
            partial.unlink(missing_ok=True)

    def execute(self, action: str) -> None:
        self.guard(require_space=action not in {"stop", "status"})
        if action == "check":
            print("磁盘 UUID、挂载、属主管理、目录权限和部署材料检查通过；未创建/启动 VM")
            return
        if action == "compose-config":
            self.run(self.compose("config", "--quiet"))
            print("四服务 Compose 配置解析通过；未构建或部署")
            return
        if action == "vm-start":
            image = self.config.get("vm_disk_image")
            extra = []
            if image:
                path = Path(image)
                contained(path, self.root)
                digest = hashlib.sha512()
                with path.open("rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(block)
                if digest.hexdigest() != self.config.get("vm_disk_image_sha512"):
                    raise GuardError("基础镜像本地文件 SHA512 不匹配")
                extra = ["--disk-image", str(path)]
            self.run(["colima", "start", "-p", self.profile, "--activate=false", "--ssh-config=false",
                      "--cpus", str(self.config["vm_cpus"]), "--memory", str(self.config["vm_memory_gib"]),
                      "--disk", str(self.config["vm_disk_gib"]), "--vm-type", "vz", "--mount-type", "virtiofs",
                      "--mount", str(self.workspace), *extra])
            if self.context != "colima-" + self.profile:
                endpoint = "unix://" + str(self.root / "colima" / self.profile / "docker.sock")
                existing = subprocess.run(["docker", "context", "inspect", self.context], env=self.env,
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                if existing.returncode != 0:
                    self.run(["docker", "context", "create", self.context, "--docker", "host=" + endpoint])
            self.daemon_guard()
            return
        if action == "up":
            self.restored_guard()
        self.daemon_guard()
        if action in {"up", "postgres-up"}:
            self.images_guard()
        if action == "build":
            if self.reuse:
                raise GuardError("复用迁移模式禁止构建/下载镜像；先核对已有镜像")
            self.run(self.compose("build"))
        elif action == "postgres-up":
            if self.config.get("reuse_postgres_container"):
                self.run(self.compose("start", "postgres"))
            else:
                self.run(self.compose("up", "-d", "--no-build", "--pull", "never", "--no-deps", "postgres"))
        elif action == "up":
            if self.config.get("reuse_postgres_container"):
                self.run(self.compose("start", "postgres"))
                self.run(self.compose("up", "-d", "--no-build", "--pull", "never", "--no-deps", "backend", "frontend", "harness"))
            else:
                self.run(self.compose("up", "-d", "--no-build", "--pull", "never"))
        elif action == "status":
            self.run(self.compose("ps", "-a"))
        elif action == "backup":
            self.backup()
        elif action == "stop":
            running = self.run(self.compose("ps", "-q", "--status", "running", "postgres"),
                               capture_output=True, text=True).stdout.strip()
            if running:
                pending = self.run(self.compose("exec", "-T", "postgres", "psql", "-X", "-U", "postgres",
                                               "-d", "assistant", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                                               "SELECT count(*) FROM rag_requests WHERE status='pending'"),
                                   capture_output=True, text=True).stdout.strip()
                if pending != "0":
                    raise GuardError("仍有 pending 任务或无法核对；先停止提交并等待任务终态，或通过既有 API 明确取消")
            self.run(self.compose("stop", "--timeout", "30"))
            self.run(["colima", "stop", "-p", self.profile])
            print("服务和实例已停止；确认系统无磁盘占用后再弹出外接盘")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "compose-config", "vm-start", "build", "postgres-up",
                                          "up", "stop", "status", "backup"])
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    config_path = args.config or repo / "deploy/external_disk.json"
    try:
        os.umask(0o077)
        private_path(config_path)
        deployment = Deployment(repo, json.loads(config_path.read_text()))
        deployment.execute(args.action)
    except (GuardError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as exc:
        print(f"拒绝执行：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
