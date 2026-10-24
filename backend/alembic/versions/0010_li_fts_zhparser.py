"""li 节点表 text_search_tsv 中文分词迁移（zhparser，roadmap B2，默认 no-op）。

li 表（``data_<LLAMA_INDEX_TABLE>``）DDL 归 llama-index 管：建表时生成列固化
为 ``to_tsvector('<text_search_config>', text)``，已有表不会因设置变化自动改列。
本迁移在 ``LLAMA_INDEX_FTS_CONFIG=zh`` 且 PostgreSQL 后端时执行：

1. 确保 zhparser 扩展存在（stock PG 镜像会在 CREATE EXTENSION 处明确报错，
   提示先换 deploy/postgres 自定义镜像）；
2. 确保 zh 中文分词配置存在且词性映射完整（与
   deploy/postgres/initdb/00-zhparser.sql 逐字一致，两侧改动必须同步）；
3. 把 text_search_tsv 生成列换成 zh 并重建 GIN 索引（生成列按新定义全量
   重算：不需要重嵌入，存量 release 继续有效）。表不存在（尚未 ingest）
   时跳过换列，首次 ingest 会按新配置直接建表。

默认 simple 时 no-op（revision 照常记录；stock PG 开发机无 zhparser 不受
影响）。与 0009 同理：开启开关后已应用过本 revision 的库需先 downgrade 再
upgrade（或手工执行同等 SQL）。换列持有 ACCESS EXCLUSIVE 锁并全表重写
（语料千级行秒级完成），建议在维护窗口执行。

downgrade 不读开关：无条件把列换回 simple 并重建索引（回退语义即撤销本
revision 的改动）；zhparser 扩展与 zh 配置保留不删，避免误伤其他用途。
"""
import re

from alembic import op

from app.config import get_settings

revision = "0010_li_fts_zhparser"
down_revision = "0009_li_hnsw_index"
branch_labels = None
depends_on = None

_SQL_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# 与 deploy/postgres/initdb/00-zhparser.sql 逐字一致，两侧改动必须同步。
# 词性映射覆盖 zhparser v2.3 词法类型表的全部 26 个类型（以
# `SELECT * FROM ts_token_type('zhparser')` 为准；scws 更细的词性标签由
# zhparser 内部归并到这 26 类，映射表写超出集合的类型会直接报错）。
# 类型名一律双引号引用（避免 as 一类 SQL 保留字冲突）。
_ZH_TOKEN_TYPES = (
    '"a","b","c","d","e","f","g","h","i","j","k","l","m",'
    '"n","o","p","q","r","s","t","u","v","w","x","y","z"'
)


def _data_table() -> str:
    configured = get_settings().llama_index_table
    if not _SQL_IDENTIFIER.fullmatch(configured):
        raise ValueError("LLAMA_INDEX_TABLE must be a PostgreSQL identifier")
    return f"data_{configured}"


def _swap_column_sql(table: str, index: str, regconfig: str) -> str:
    # 全程幂等：DROP COLUMN IF EXISTS 级联删除旧 GIN 索引；EXECUTE 用 $X$
    # 嵌套 dollar-quote 避免单引号转义。
    return f"""
DO $$
BEGIN
  IF to_regclass('{table}') IS NOT NULL THEN
    EXECUTE $X$ALTER TABLE {table} DROP COLUMN IF EXISTS text_search_tsv$X$;
    EXECUTE $X$ALTER TABLE {table} ADD COLUMN text_search_tsv tsvector
      GENERATED ALWAYS AS (to_tsvector('{regconfig}'::regconfig, text)) STORED$X$;
    EXECUTE $X$CREATE INDEX IF NOT EXISTS {index} ON {table} USING gin (text_search_tsv)$X$;
  END IF;
END $$;
"""


def upgrade() -> None:
    settings = get_settings()
    if settings.database_backend not in {"postgres", "postgresql"}:
        return
    if settings.llama_index_fts_config != "zh":
        print("0010_li_fts_zhparser: LLAMA_INDEX_FTS_CONFIG != zh，跳过中文分词迁移（no-op）")
        return
    table = _data_table()
    index = f"{settings.llama_index_table}_idx"
    op.execute("CREATE EXTENSION IF NOT EXISTS zhparser")
    # CREATE TEXT SEARCH CONFIGURATION 不支持 IF NOT EXISTS，用 DO 块判存在。
    op.execute(
        """
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zh') THEN
    EXECUTE 'CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser)';
  END IF;
END $$;
"""
    )
    op.execute(f"ALTER TEXT SEARCH CONFIGURATION zh DROP MAPPING IF EXISTS FOR {_ZH_TOKEN_TYPES}")
    op.execute(f"ALTER TEXT SEARCH CONFIGURATION zh ADD MAPPING FOR {_ZH_TOKEN_TYPES} WITH simple")
    op.execute(_swap_column_sql(table, index, "zh"))


def downgrade() -> None:
    settings = get_settings()
    if settings.database_backend not in {"postgres", "postgresql"}:
        return
    table = _data_table()
    index = f"{settings.llama_index_table}_idx"
    op.execute(_swap_column_sql(table, index, "simple"))
