"""li 节点表 HNSW 近似向量索引（对照实验，开关默认关）。

li 表（``data_<LLAMA_INDEX_TABLE>``）DDL 归 llama-index 管，本迁移只加索引
不改列；索引名与 ``PGVectorStore._create_hnsw_index`` 一致，ingest 路径
（perform_setup=True 且开关开启）幂等确保同一索引，两条路径不冲突。

升级仅在 ``LLAMA_INDEX_HNSW_ENABLED=true`` 且 PostgreSQL 后端时执行，否则
no-op（revision 照常记录，便于后续开启时重放：开启开关后
``alembic upgrade head`` 不会重跑已应用的 revision，需先 downgrade 再
upgrade，或手工执行同等 CREATE INDEX 语句——见 docs/archive/2026-09-18/rag_roadmap.md B1）。
回退无条件 DROP INDEX，不依赖开关状态。
"""
import re

from alembic import op

from app.config import get_settings

revision = "0009_li_hnsw_index"
down_revision = "0008_rag_product"
branch_labels = None
depends_on = None

_SQL_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _data_table() -> str:
    configured = get_settings().llama_index_table
    if not _SQL_IDENTIFIER.fullmatch(configured):
        raise ValueError("LLAMA_INDEX_TABLE must be a PostgreSQL identifier")
    return f"data_{configured}"


def upgrade() -> None:
    settings = get_settings()
    if settings.database_backend not in {"postgres", "postgresql"}:
        return
    if not settings.llama_index_hnsw_enabled:
        print("0009_li_hnsw_index: LLAMA_INDEX_HNSW_ENABLED=false，跳过 HNSW 建索引（no-op）")
        return
    table = _data_table()
    op.execute(
        f"CREATE INDEX IF NOT EXISTS {table}_embedding_idx ON {table} "
        f"USING hnsw (embedding vector_cosine_ops) "
        f"WITH (m = {int(settings.llama_index_hnsw_m)}, "
        f"ef_construction = {int(settings.llama_index_hnsw_ef_construction)})"
    )


def downgrade() -> None:
    settings = get_settings()
    if settings.database_backend not in {"postgres", "postgresql"}:
        return
    op.execute(f"DROP INDEX IF EXISTS {_data_table()}_embedding_idx")
