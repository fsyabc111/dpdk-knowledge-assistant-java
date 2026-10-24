"""P0 动态 oracle 隔离入口；必须在任何 app 导入前调用。"""
from __future__ import annotations
import ipaddress
import os
from pathlib import Path
import socket
import sys
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '80b29135faddd16d5e39fdbf0633291ea6a20489'
COMMENT = 'java-migration-p0-synthetic-80b29135'
DATABASES = {'p0_python_oracle', 'p0_candidate_oracle', 'p0_benchmark_oracle'}
PORT = 55473


def validate_url(url: str) -> dict:
    parsed = urlparse(url)
    if (parsed.scheme != 'postgresql' or parsed.hostname != '127.0.0.1' or
            parsed.port != PORT or parsed.username != 'p0_oracle' or
            parsed.password is not None or parsed.path[1:] not in DATABASES or parsed.query):
        raise RuntimeError('拒绝目标：必须显式指定独立 P0 回环 PostgreSQL URL')
    return {'host': parsed.hostname, 'port': parsed.port, 'role': parsed.username,
            'database': parsed.path[1:]}


def validate_target(url: str) -> dict:
    expected = validate_url(url)
    import psycopg
    from psycopg.rows import dict_row
    with psycopg.connect(url, row_factory=dict_row) as conn:
        row = conn.execute("SELECT current_database() AS database,current_user AS role, "
            "host(inet_server_addr()) AS host,inet_server_port() AS port, "
            "shobj_description(oid,'pg_database') AS comment, "
            "current_setting('data_directory') AS data_directory,version() AS version "
            "FROM pg_database WHERE datname=current_database()").fetchone()
    if any(row[k] != v for k, v in expected.items()) or row['comment'] != COMMENT:
        raise RuntimeError('拒绝数据库现场身份或 comment 不符')
    if Path(row['data_directory']).resolve() != (ROOT/'.migration-artifacts/p0/pgdata').resolve():
        raise RuntimeError('拒绝数据库 data_directory 不符')
    return dict(row)


def configure(database: str = 'p0_python_oracle') -> dict:
    if database not in DATABASES:
        raise RuntimeError('未知 P0 数据库')
    if any(name == 'app.main' for name in sys.modules):
        raise RuntimeError('必须先隔离再导入应用')
    url = f'postgresql://p0_oracle@127.0.0.1:{PORT}/{database}'
    validate_url(url)
    # 清空 ambient provider / proxy / 数据库配置，保留操作系统基础变量。
    keep = {key: value for key, value in os.environ.items()
            if key in {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'SYSTEMROOT'}}
    os.environ.clear()
    os.environ.update(keep)
    os.environ.update({
        'PGOPTIONS':'-c timezone=UTC','DATABASE_BACKEND':'postgres','DATABASE_URL':url,'AUTH_MODE':'account',
        'BCRYPT_ROUNDS':'4','APP_ENV':'p0-synthetic','LOG_LEVEL':'ERROR','LOG_FILE':'',
        'LLM_API_BASE':'http://127.0.0.1:9/v1','LLM_API_KEY':'p0-synthetic-no-secret',
        'EMBEDDING_API_BASE':'http://127.0.0.1:9/v1','EMBEDDING_API_KEY':'p0-synthetic-no-secret',
        'HARNESS_MCP_URL':'http://127.0.0.1:9/mcp','MAINTENANCE_API_KEY':'p0-synthetic-maintenance',
        'RATE_LIMIT_CHAT_PER_MINUTE':'100000','LLM_DAILY_QUOTA_DEFAULT':'100000',
        'GRADING_DAILY_QUOTA':'100000','DATABASE_POOL_MAX_SIZE':'20',
        'ASSISTANT_PROJECT_PATH':str(ROOT),
        'DPDK_REPO_PATH':str(ROOT/'.migration-artifacts/p0/no-external-repo'),
        'KNOWLEDGE_CONFIG':str(ROOT/'migration/fixtures/p0-v1/knowledge.yml'),
    })
    sys.path.insert(0, str(ROOT/'backend'))
    from app.config import Settings, get_settings
    Settings.model_config['env_file'] = None
    get_settings.cache_clear()
    settings = get_settings()
    if settings.database_url != url or Settings.model_config.get('env_file') is not None:
        raise RuntimeError('应用设置未隔离')
    identity = validate_target(url)
    install_network_guard()
    return identity


def install_network_guard() -> None:
    if getattr(socket.socket.connect, '_p0_guard', False):
        return
    original_connect, original_connect_ex = socket.socket.connect, socket.socket.connect_ex
    def check(address):
        if isinstance(address, tuple):
            host = address[0]
            try:
                allowed = ipaddress.ip_address(host).is_loopback
            except ValueError:
                allowed = host == 'localhost'
            if not allowed:
                raise RuntimeError('P0 禁止非回环出网')
    def connect(self, address):
        check(address)
        return original_connect(self, address)
    def connect_ex(self, address):
        check(address)
        return original_connect_ex(self, address)
    connect._p0_guard = True
    socket.socket.connect, socket.socket.connect_ex = connect, connect_ex


def initialize_schema() -> None:
    """执行冻结 Python Alembic 链；不修改 DDL。仅首次空库允许。"""
    from app.config import get_settings
    identity = validate_target(get_settings().database_url)
    import psycopg
    with psycopg.connect(get_settings().database_url) as conn:
        version = conn.execute("SELECT to_regclass('alembic_version')").fetchone()[0]
        if version is not None:
            actual = conn.execute('SELECT version_num FROM alembic_version').fetchall()
            if actual != [('0015_explain_purpose',)]:
                raise RuntimeError(f'已有schema不符合预期: {actual}')
            return
    from app.db import init_db
    init_db()
    from alembic import command
    from alembic.config import Config
    cfg = Config(str(ROOT/'backend/alembic.ini'))
    cfg.set_main_option('script_location', str(ROOT/'backend/alembic'))
    # 冻结 env.py 使用无驱动 URL；显式绑定已锁定 psycopg3 适配器。
    import sqlalchemy
    from unittest.mock import patch
    original = sqlalchemy.engine_from_config
    def engine_from_config(section, *args, **kwargs):
        section = dict(section)
        section['sqlalchemy.url'] = section['sqlalchemy.url'].replace('postgresql://','postgresql+psycopg://',1)
        return original(section,*args,**kwargs)
    with patch('sqlalchemy.engine_from_config',engine_from_config):
        command.upgrade(cfg, 'head')
    init_db()
