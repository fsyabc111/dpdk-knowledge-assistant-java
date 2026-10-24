"""S2 迁移在独立临时 SQLite 上验证；不读取开发库配置。"""
import importlib.util
from pathlib import Path

import sqlalchemy as sa
from alembic.migration import MigrationContext
from alembic.operations import Operations


def test_s2_upgrade_downgrade_preserves_identity(tmp_path):
    migration_path = Path(__file__).resolve().parents[1] / "alembic/versions/0012_request_identity_session_release.py"
    spec = importlib.util.spec_from_file_location("s2_migration", migration_path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    engine = sa.create_engine(f"sqlite:///{tmp_path / 'migration.db'}")
    with engine.begin() as conn:
        conn.exec_driver_sql("CREATE TABLE assistant_sessions(id TEXT PRIMARY KEY)")
        conn.exec_driver_sql("CREATE TABLE rag_requests(id TEXT PRIMARY KEY, session_id TEXT, user_id TEXT)")
        conn.exec_driver_sql("INSERT INTO assistant_sessions VALUES ('session-old')")
        conn.exec_driver_sql("INSERT INTO rag_requests VALUES ('legacy-id', 'session-old', 'student')")
        with Operations.context(MigrationContext.configure(conn)):
            migration.upgrade()
            migration.upgrade()  # init_db 与 migration 重复部署不破坏已有身份。
            row = conn.exec_driver_sql("SELECT * FROM rag_requests").mappings().one()
            assert row['id'] == row['request_id'] == 'legacy-id'
            assert row['input_hash'] is None
            assert row['initial_request'] == 1
            conn.exec_driver_sql("UPDATE assistant_sessions SET rag_release_id='release-a'")
            conn.exec_driver_sql("INSERT INTO rag_requests VALUES ('task-new','session-old','student','client-new','digest',0)")
            migration.downgrade()
            assert conn.exec_driver_sql("SELECT input_hash FROM rag_requests WHERE id='task-new'").scalar_one() == 'digest'
            assert conn.exec_driver_sql("SELECT rag_release_id FROM assistant_sessions").scalar_one() == 'release-a'
            migration.upgrade()
        indexes = {index['name'] for index in sa.inspect(conn).get_indexes('rag_requests')}
        assert {'idx_rag_request_session_key', 'idx_rag_request_initial_key'} <= indexes
    engine.dispose()
