"""P5 公共 PG 隔离工具：四项显式配置、先只读核验、manifest 所有权清理。

无模块级数据库连接；依赖 psycopg 在真正连接时才加载。不会读取 .env、PG* 默认值、
旧 P0 库或旧业务库。口令只保留在内存及连接参数，manifest 不含凭据。
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit, unquote


class IsolationError(RuntimeError):
    """身份、配置或所有权不符，调用方必须中止写入。"""


@dataclass(frozen=True)
class OwnedDatabase:
    name: str
    url: str
    comment: str
    round_id: str


class PgIsolation:
    REQUIRED = ("DPDK_TEST_PG_URL", "DPDK_TEST_PG_USER", "DPDK_TEST_PG_PASSWORD", "DPDK_TEST_PG_DATA_DIR")
    # 消费者必须显式选择自己的命名空间；禁止旧 p0/p1_candidate 或任意前缀。
    PREFIXES = frozenset(("p5_contract_", "p5_ui_", "p5_capacity_", "p5_resilience_", "p5_deployment_", "p1_t_p5_"))

    def __init__(self, *, url, user, password, data_dir, manifest_path, allowed_prefixes, connect=None):
        source = url.removeprefix("jdbc:")
        parsed = urlsplit(source)
        if parsed.scheme not in ("postgresql", "postgres") or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise IsolationError("只允许显式 loopback PostgreSQL URL")
        if parsed.port is None or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise IsolationError("URL 必须显式端口，且不含凭据、query 或 fragment")
        self.maintenance_database = unquote(parsed.path.lstrip("/"))
        if self.maintenance_database != "postgres":
            raise IsolationError("维护 URL 必须显式指向 postgres；不连接旧业务/P0库")
        if not user or not password or not Path(data_dir).is_absolute():
            raise IsolationError("user/password/data_dir 必须显式且 data_dir 为绝对路径")
        prefixes = tuple(allowed_prefixes)
        if not prefixes or any(p not in self.PREFIXES for p in prefixes):
            raise IsolationError("不允许的 P5 数据库前缀")
        self.host, self.port, self.user = parsed.hostname, parsed.port, user
        self.password, self.data_dir = password, Path(data_dir).resolve()
        self.base_url = source[:source.rfind("/") + 1]
        self.manifest_path, self.allowed_prefixes = Path(manifest_path), prefixes
        self._connector = connect
        if self.manifest_path.exists():
            self.manifest = json.loads(self.manifest_path.read_text())
            expected = {"host": self.host, "port": self.port, "user": self.user, "data_dir": str(self.data_dir)}
            if self.manifest.get("instance") != expected or self.manifest.get("prefixes") != list(prefixes):
                raise IsolationError("既有 manifest 与显式实例/命名空间不符")
        else:
            self.manifest = {"version": 1, "round_id": secrets.token_hex(8), "prefixes": list(prefixes),
                "instance": {"host": self.host, "port": self.port, "user": self.user, "data_dir": str(self.data_dir)}, "owned": {}}

    @classmethod
    def from_env(cls, manifest_path, *, allowed_prefixes=("p5_contract_",), env=None, connect=None):
        env = os.environ if env is None else env
        missing = [k for k in cls.REQUIRED if not env.get(k)]
        if missing:
            raise IsolationError("缺少显式配置：" + ", ".join(missing))
        return cls(url=env[cls.REQUIRED[0]], user=env[cls.REQUIRED[1]], password=env[cls.REQUIRED[2]],
                   data_dir=env[cls.REQUIRED[3]], manifest_path=manifest_path,
                   allowed_prefixes=allowed_prefixes, connect=connect)

    def _connect(self, database):
        if self._connector is None:
            import psycopg
            connector = psycopg.connect
        else:
            connector = self._connector
        # 所有字段显式传入，PGHOST/PGDATABASE 等 ambient 不可改变目标。
        return connector(host=self.host, port=self.port, user=self.user, password=self.password,
                         dbname=database, autocommit=True, connect_timeout=5,
                         options="-c timezone=UTC -c application_name=j5-isolated-acceptance")

    def _identity(self, conn, database):
        row = conn.execute("SELECT current_user, current_database(), current_setting('data_directory')").fetchone()
        actual_dir = Path(row[2]).resolve()
        if row[0] != self.user or row[1] != database or not actual_dir.is_relative_to(self.data_dir):
            raise IsolationError("PG 身份不符；拒绝写入")
        return {"user": row[0], "database": row[1], "data_dir": str(actual_dir)}

    def verify_instance(self):
        with self._connect(self.maintenance_database) as conn:
            return self._identity(conn, self.maintenance_database)

    def _persist(self):
        self.manifest_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.manifest_path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(self.manifest, f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.manifest_path)
        os.chmod(self.manifest_path, 0o600)

    def create(self, label, *, prefix=None):
        prefix = prefix or self.allowed_prefixes[0]
        if prefix not in self.allowed_prefixes or not re.fullmatch(r"[a-z][a-z0-9_]{0,19}", label):
            raise IsolationError("非法 label 或未获准的前缀")
        name = prefix + self.manifest["round_id"] + "_" + label
        if len(name) > 63:
            raise IsolationError("数据库名超过63字节")
        comment = "p5-owned:" + self.manifest["round_id"] + ":" + name
        with self._connect(self.maintenance_database) as conn:
            self._identity(conn, self.maintenance_database)  # 第一条 SQL 只读，任何写入前执行
            if conn.execute("SELECT 1 FROM pg_database WHERE datname=%s", (name,)).fetchone():
                raise IsolationError("目标库已存在；拒绝复用/覆盖")
            # name/comment 全由受限字符集构造；不插值任何口令/用户输入 SQL。
            conn.execute('CREATE DATABASE "' + name + '"')
            conn.execute('COMMENT ON DATABASE "' + name + '" IS \'' + comment + "'")
        db = OwnedDatabase(name, self.base_url + name, comment, self.manifest["round_id"])
        self.manifest["owned"][name] = {**asdict(db), "state": "created"}
        self._persist()
        self.verify_owned(db)
        return db

    def verify_owned(self, db):
        recorded = self.manifest["owned"].get(db.name)
        if not recorded or recorded.get("state") != "created" or recorded != {**asdict(db), "state": "created"}:
            raise IsolationError("数据库不属于本轮 manifest")
        if db.round_id != self.manifest["round_id"] or not db.name.startswith(self.allowed_prefixes):
            raise IsolationError("数据库轮次或前缀不符")
        with self._connect(self.maintenance_database) as conn:
            self._identity(conn, self.maintenance_database)
            row = conn.execute("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=%s", (db.name,)).fetchone()
            if row is None or row[0] != db.comment:
                raise IsolationError("数据库 comment 不符；拒绝写入/删除")
        with self._connect(db.name) as conn:
            return self._identity(conn, db.name)

    def drop(self, db):
        self.verify_owned(db)
        with self._connect(self.maintenance_database) as conn:
            self._identity(conn, self.maintenance_database)
            # 再查comment避免预检到DROP间的明显目标漂移；无FORCE，不终止他人连接。
            row = conn.execute("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=%s", (db.name,)).fetchone()
            if row is None or row[0] != db.comment:
                raise IsolationError("DROP 前 comment 漂移；零删除退出")
            conn.execute('DROP DATABASE "' + db.name + '"')
        self.manifest["owned"][db.name]["state"] = "dropped"
        self._persist()
