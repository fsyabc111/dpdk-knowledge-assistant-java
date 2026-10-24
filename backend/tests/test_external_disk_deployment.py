"""外接部署拒绝路径：不接触真实磁盘、daemon 或数据库。"""
import importlib.util
import json
import os
import subprocess
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[2] / "deploy/external_disk.py"
SPEC = importlib.util.spec_from_file_location("external_disk_deployment", MODULE_PATH)
external = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(external)


@pytest.fixture
def prepared(tmp_path, monkeypatch):
    volume = tmp_path / "volume"
    root = volume / "dpdk-runtime"
    repo = root / "workspace/dpdk/ai_chat_assistant"
    for path in [root, root / "colima", root / "cache/colima", root / "workspace",
                 root / "backups", root / "migration", repo / "deploy/certs",
                 root / "workspace/teaching_harness"]:
        path.mkdir(parents=True, exist_ok=True)
        path.chmod(0o700)
    files = [repo / "deploy/certs/server.key", repo / "deploy/certs/server.crt",
             repo / "deploy/external_disk.json", repo / "docker-compose.yml",
             repo / "docker-compose.external.yml", root / "workspace/teaching_harness/Dockerfile"]
    for file in files:
        file.write_text("synthetic")
        file.chmod(0o600)
    env = repo / "deploy/.env"
    env.write_text("AUTH_MODE=account\nDATABASE_BACKEND=postgres\nHARNESS_MCP_URL=http://harness:8100/mcp\n"
                   "DATABASE_URL=postgresql://postgres:synthetic@postgres:5432/assistant\nDPDK_REPO_PATH=/data/dpdk\n")
    env.chmod(0o600)
    config = {"volume": str(volume), "volume_uuid": "synthetic-uuid", "runtime_root": str(root),
              "profile": "dpdk-ext", "compose_project": "dpdk-assistant-ext", "min_free_gib": 20,
              "selected_source": "host-assistant-dev", "selected_release_id": "synthetic-release",
              "emergency_backup_dir": str(tmp_path / "independent-backup")}
    info = {"MountPoint": str(volume), "VolumeUUID": config["volume_uuid"],
            "FilesystemType": "apfs", "VolumeReadOnly": False, "GlobalPermissionsEnabled": True}
    monkeypatch.setattr(external.subprocess, "check_output", lambda *a, **kw: external.plistlib.dumps(info))
    monkeypatch.setattr(external.os.path, "ismount", lambda path: True)
    monkeypatch.setattr(external.shutil, "disk_usage", lambda path: type("Usage", (), {"free": 100 * 1024**3})())
    return external.Deployment(repo, config), info


@pytest.mark.parametrize("field,value,message", [
    ("VolumeUUID", "wrong-disk", "UUID"),
    ("MountPoint", "", "未挂载"),
    ("GlobalPermissionsEnabled", False, "属主管理"),
    ("VolumeReadOnly", True, "可写 APFS"),
])
def test_wrong_volume_refused_without_creating_vm(prepared, field, value, message):
    deployment, info = prepared
    info[field] = value
    with pytest.raises(external.GuardError, match=message):
        deployment.execute("check")
    assert not (deployment.root / "colima/_lima").exists()


def test_low_space_refused_but_stop_preflight_still_allowed(prepared, monkeypatch):
    deployment, _ = prepared
    monkeypatch.setattr(external.shutil, "disk_usage", lambda path: type("Usage", (), {"free": 0})())
    with pytest.raises(external.GuardError, match="空间不足"):
        deployment.guard()
    deployment.guard(require_space=False)


def test_symlink_cannot_redirect_colima_to_internal_disk(prepared, tmp_path):
    deployment, _ = prepared
    location = deployment.root / "colima"
    location.rmdir()
    location.symlink_to(tmp_path)
    with pytest.raises(external.GuardError, match="越过"):
        deployment.guard()


def test_credentials_require_private_permissions(prepared):
    deployment, _ = prepared
    (deployment.repo / "deploy/.env").chmod(0o644)
    with pytest.raises(external.GuardError, match="禁止组"):
        deployment.guard()


def test_container_cannot_accidentally_connect_to_host_database(prepared):
    deployment, _ = prepared
    env = deployment.repo / "deploy/.env"
    env.write_text(env.read_text().replace("@postgres:5432/assistant", "@host.docker.internal:5432/assistant_dev"))
    with pytest.raises(external.GuardError, match="数据库必须"):
        deployment.guard()


def test_wrong_docker_socket_refused(prepared, monkeypatch):
    deployment, _ = prepared
    monkeypatch.setattr(deployment, "run", lambda *a, **kw: subprocess.CompletedProcess([], 0, "unix:///var/run/docker.sock\n"))
    with pytest.raises(external.GuardError, match="socket"):
        deployment.daemon_guard()


def test_up_without_restoration_record_does_not_start_services(prepared, monkeypatch):
    deployment, _ = prepared
    monkeypatch.setattr(deployment, "daemon_guard", lambda: None)
    calls = []
    monkeypatch.setattr(deployment, "run", lambda cmd, **kw: calls.append(cmd))
    with pytest.raises(external.GuardError, match="尚未验证"):
        deployment.execute("up")
    assert calls == []


def test_restoration_record_cannot_belong_to_other_source(prepared):
    deployment, _ = prepared
    (deployment.root / "migration/restored-and-verified.json").write_text(json.dumps({"phase": "restored-and-verified", "source": "old-compose-assistant"}))
    with pytest.raises(external.GuardError, match="不一致"):
        deployment.restored_guard()


def test_inherited_host_and_compose_paths_are_not_used(prepared, monkeypatch):
    deployment, _ = prepared
    monkeypatch.setenv("DOCKER_HOST", "unix:///old.sock")
    monkeypatch.setenv("DPDK_REPO_HOST_PATH", "/old/source")
    deployment = external.Deployment(deployment.repo, deployment.config)
    assert "DOCKER_HOST" not in deployment.env
    assert deployment.env["DPDK_REPO_HOST_PATH"] == str(deployment.workspace / "dpdk")
    command = deployment.compose("config", "--quiet")
    assert command[2] == "colima-dpdk-ext"
    assert "dpdk-assistant-ext" in command
    assert "--env-file" in command


def test_pending_tasks_prevent_stop(prepared, monkeypatch):
    deployment, _ = prepared
    monkeypatch.setattr(deployment, "daemon_guard", lambda: None)
    calls = []

    def run(cmd, **kwargs):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, "postgres-container\n" if "ps" in cmd else "1\n")

    monkeypatch.setattr(deployment, "run", run)
    with pytest.raises(external.GuardError, match="pending"):
        deployment.execute("stop")
    assert not any("stop" in command for command in calls)


def test_backup_containing_temporary_source_is_not_retained(prepared, monkeypatch):
    deployment, _ = prepared

    def run(cmd, **kwargs):
        if "pg_dump" in cmd:
            kwargs["stdout"].write(b"synthetic-archive")
            return subprocess.CompletedProcess(cmd, 0)
        return subprocess.CompletedProcess(cmd, 0, "1; 0 0 TABLE DATA public rag_task_inputs postgres\n")

    monkeypatch.setattr(deployment, "run", run)
    with pytest.raises(external.GuardError, match="临时输入"):
        deployment.backup()
    assert not list((deployment.root / "backups").iterdir())


def test_successful_backup_has_independent_identical_copy(prepared, monkeypatch):
    deployment, _ = prepared

    def run(cmd, **kwargs):
        if "pg_dump" in cmd:
            kwargs["stdout"].write(b"synthetic-archive")
            assert "--exclude-table-data=public.rag_task_inputs" in cmd
            return subprocess.CompletedProcess(cmd, 0)
        return subprocess.CompletedProcess(cmd, 0, "1; 0 0 TABLE public rag_task_inputs postgres\n")

    monkeypatch.setattr(deployment, "run", run)
    previous = os.umask(0o077)
    try:
        deployment.backup()
    finally:
        os.umask(previous)
    archives = list((deployment.root / "backups").glob("*.dump"))
    assert len(archives) == 1
    mirror = Path(deployment.config["emergency_backup_dir"]) / archives[0].name
    assert mirror.read_bytes() == archives[0].read_bytes()
    assert mirror.stat().st_mode & 0o077 == 0


def test_reused_profile_requires_verified_copy_record(prepared):
    deployment, _ = prepared
    config = dict(deployment.config, profile="dpdk", docker_context="colima-dpdk-ext", reuse_existing_instance=True)
    reused = external.Deployment(deployment.repo, config)
    with pytest.raises(external.GuardError, match="磁盘校验"):
        reused.guard()


def test_reused_copy_cannot_run_while_original_runs(prepared, monkeypatch):
    deployment, info = prepared
    config = dict(deployment.config, profile="dpdk", docker_context="colima-dpdk-ext", reuse_existing_instance=True)
    reused = external.Deployment(deployment.repo, config)
    record = {"copy_verified": True, "profile": "dpdk", "volume_uuid": config["volume_uuid"],
              "source_colima_home": "/synthetic/original"}
    (reused.root / "migration/relocated-vm.json").write_text(json.dumps(record))

    def output(command, **kwargs):
        if command[0] == "colima":
            return '{"name":"dpdk","status":"Running"}\n'
        return external.plistlib.dumps(info)

    monkeypatch.setattr(external.subprocess, "check_output", output)
    with pytest.raises(external.GuardError, match="原实例未确认停止"):
        reused.guard()


def test_existing_images_start_with_pull_and_build_disabled(prepared, monkeypatch):
    deployment, _ = prepared
    calls = []
    monkeypatch.setattr(deployment, "daemon_guard", lambda: None)
    monkeypatch.setattr(deployment, "restored_guard", lambda: None)
    monkeypatch.setattr(deployment, "run", lambda command, **kwargs: calls.append(command))
    deployment.execute("postgres-up")
    deployment.execute("up")
    assert all("--no-build" in command and command[command.index("--pull") + 1] == "never" for command in calls)


def test_legacy_postgres_is_started_without_recreation(prepared, monkeypatch):
    deployment, _ = prepared
    deployment.config["reuse_postgres_container"] = True
    calls = []
    monkeypatch.setattr(deployment, "daemon_guard", lambda: None)
    monkeypatch.setattr(deployment, "restored_guard", lambda: None)
    monkeypatch.setattr(deployment, "run", lambda command, **kwargs: calls.append(command))
    deployment.execute("up")
    assert calls[0][-2:] == ["start", "postgres"]
    assert calls[1][-3:] == ["backend", "frontend", "harness"]
    assert "--no-deps" in calls[1] and "--no-build" in calls[1]


def test_restoration_record_can_match_user_selected_legacy_schema(prepared):
    deployment, _ = prepared
    deployment.config["target_alembic"] = "0013_task_recovery"
    record = {"phase": "restored-and-verified", "volume_uuid": deployment.config["volume_uuid"],
              "compose_project": deployment.config["compose_project"], "context": deployment.context,
              "source": deployment.config["selected_source"], "release_id": deployment.config["selected_release_id"],
              "alembic": "0013_task_recovery"}
    (deployment.root / "migration/restored-and-verified.json").write_text(json.dumps(record))
    deployment.restored_guard()


def test_preserved_image_tag_cannot_be_overwritten(prepared, monkeypatch):
    deployment, _ = prepared
    deployment.config["preserve_original_images"] = True
    marker = {"volume_uuid": deployment.config["volume_uuid"], "compose_project": deployment.config["compose_project"],
              "images": {"synthetic-backend:old": "sha256:original"}}
    (deployment.root / "migration/preserved-images.json").write_text(json.dumps(marker))
    monkeypatch.setattr(deployment, "run", lambda *a, **kw: subprocess.CompletedProcess([], 0, "sha256:different\n"))
    with pytest.raises(external.GuardError, match="镜像标签已变化"):
        deployment.images_guard()


def test_original_pg_container_cannot_be_silently_recreated(prepared, monkeypatch):
    deployment, _ = prepared
    deployment.config["preserve_original_images"] = True
    marker = {"volume_uuid": deployment.config["volume_uuid"], "compose_project": deployment.config["compose_project"],
              "images": {}, "postgres": {"container": "synthetic-pg", "container_id": "original", "image_id": "sha256:original"}}
    (deployment.root / "migration/preserved-images.json").write_text(json.dumps(marker))
    monkeypatch.setattr(deployment, "run", lambda *a, **kw: subprocess.CompletedProcess([], 0, "new sha256:new\n"))
    with pytest.raises(external.GuardError, match="原 PostgreSQL 容器已变化"):
        deployment.images_guard()


def test_retired_native_alias_is_allowed_only_with_verified_record(prepared, tmp_path):
    deployment, _ = prepared
    alias = tmp_path / "native-colima"
    alias.symlink_to(deployment.root / "colima", target_is_directory=True)
    config = dict(deployment.config, profile="dpdk", docker_context="colima-dpdk-ext", reuse_existing_instance=True)
    reused = external.Deployment(deployment.repo, config)
    record = {"copy_verified": True, "profile": "dpdk", "volume_uuid": config["volume_uuid"],
              "source_colima_home": str(alias)}
    (reused.root / "migration/relocated-vm.json").write_text(json.dumps(record))
    retirement = {"phase": "aliases-installed", "volume_uuid": config["volume_uuid"], "source_colima_home": str(alias)}
    (reused.root / "migration/native-retirement.json").write_text(json.dumps(retirement))
    reused.guard()
    retirement["volume_uuid"] = "other-disk"
    (reused.root / "migration/native-retirement.json").write_text(json.dumps(retirement))
    with pytest.raises(external.GuardError, match="退役记录不匹配"):
        reused.guard()
