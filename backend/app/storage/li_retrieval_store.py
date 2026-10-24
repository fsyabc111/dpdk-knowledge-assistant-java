"""在同一 LlamaIndex 发布版本上执行有界多路召回（PG 数据面）。"""
from __future__ import annotations

import json
import logging
import re
import threading
from typing import Optional, Sequence

from ..config import get_settings
from ..db import get_conn

logger = logging.getLogger("assistant")

_REGCONFIG_RE = re.compile(r"'([^']+)'::regconfig")

# text_search_tsv 的 regconfig 是表级属性（建表/迁移时固化进生成列），进程内
# 只核对一次；切换配置必须配合 alembic 0010 + 重启，不存在运行期漂移。
_LIVE_REGCONFIG_CACHE: dict[str, Optional[str]] = {}
_LIVE_REGCONFIG_LOCK = threading.Lock()
_MISMATCH_WARNED: set[str] = set()


def live_fts_regconfig(quoted_table: str) -> Optional[str]:
    """读取 text_search_tsv 生成列当前的 regconfig；表/列不存在返回 None。

    quoted_table 是 _quoted_data_table 校验过的带引号标识符，to_regclass 接受
    同样写法且表不存在时返回 NULL（不报错）。pg_get_expr 对 search_path 内的
    配置输出裸名，对 pg_catalog 配置可能带模式限定，统一取末段归一化。
    """
    with get_conn() as conn:
        row = conn.execute(
            "SELECT pg_get_expr(d.adbin, d.adrelid) AS expr "
            "FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum "
            "WHERE d.adrelid = to_regclass(%s) AND a.attname='text_search_tsv'",
            (quoted_table,),
        ).fetchone()
    expr = row.get("expr") if row else None
    if not expr:
        return None
    match = _REGCONFIG_RE.search(expr)
    return match.group(1).split(".")[-1] if match else None


def _cached_live_fts_regconfig(table: str) -> Optional[str]:
    with _LIVE_REGCONFIG_LOCK:
        if table not in _LIVE_REGCONFIG_CACHE:
            _LIVE_REGCONFIG_CACHE[table] = live_fts_regconfig(table)
        return _LIVE_REGCONFIG_CACHE[table]


class LiNodeRetrievalStore:
    def __init__(self, table: str, release_id: str) -> None:
        self.table = table  # 调用方通过 _quoted_data_table 验证标识符。
        self.release_id = release_id

    def _ids(self, clause: str, args: tuple, order: str, limit: int,
             prelude: Optional[tuple] = None) -> list[str]:
        join = "JOIN rag_release_nodes r ON r.node_id=c.node_id" if self.release_id else ""
        version = "r.release_id=%s" if self.release_id else "TRUE"
        version_args = (self.release_id,) if self.release_id else ()
        with get_conn() as conn:
            if prelude is not None:
                conn.execute(prelude[0], prelude[1])
            rows = conn.execute(
                f"SELECT c.node_id FROM {self.table} c {join} "
                f"WHERE {version} AND ({clause}) ORDER BY {order}, c.node_id LIMIT %s",
                (*version_args, *args, min(limit, 512)),
            ).fetchall()
        return [row["node_id"] for row in rows]

    def fetch_fts_candidate_ids(self, match_query: str, limit: int) -> list[str]:
        if not match_query:
            return []
        config = get_settings().llama_index_fts_config
        live = _cached_live_fts_regconfig(self.table)
        if live is not None and live != config:
            # env 与生成列不一致（切换配置后未跑 alembic 0010，或反之）：
            # 中文/英文 token 口径不同会让 FTS 腿静默零命中，降级并每进程告警一次，
            # 其余召回腿不受影响；修复路径是跑迁移后重启。
            with _LIVE_REGCONFIG_LOCK:
                first = self.table not in _MISMATCH_WARNED
                _MISMATCH_WARNED.add(self.table)
            if first:
                logger.warning(json.dumps({
                    "event": "rag_fts_config_mismatch", "table": self.table,
                    "live_regconfig": live, "configured": config,
                }, ensure_ascii=False))
            return []
        return self._ids(
            "c.text_search_tsv @@ websearch_to_tsquery(CAST(%s AS regconfig), %s)",
            (config, match_query, config, match_query),
            "ts_rank(c.text_search_tsv, websearch_to_tsquery(CAST(%s AS regconfig), %s)) DESC", limit,
        )

    def fetch_path_candidate_ids(self, fragments: Sequence[str], limit: int) -> list[str]:
        if not fragments:
            return []
        # LIKE 通配符转义，路径片段作为文字，不扩大为任意路径匹配。
        args = tuple("%" + f.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                     for f in fragments[:20])
        return self._ids(" OR ".join("c.metadata_->>'source_path' ILIKE %s" for _ in args), args,
                         "c.metadata_->>'source_path'", limit)

    def fetch_semantic_candidate_ids(self, embedding: Sequence[float], limit: int) -> list[str]:
        if not embedding:
            return []
        vector = "[" + ",".join(str(float(x)) for x in embedding) + "]"
        prelude = None
        settings = get_settings()
        if settings.llama_index_hnsw_enabled:
            # HNSW 开启时显式固定 ef_search（事务级 GUC，退出 with 即复位），
            # 保证裸 SQL 语义腿与调参口径一致；关闭时保持 pgvector 默认。
            prelude = ("SELECT set_config('hnsw.ef_search', %s, true)",
                       (str(settings.llama_index_hnsw_ef_search),))
        return self._ids("c.embedding IS NOT NULL", (vector,), "c.embedding <=> %s::vector", limit,
                         prelude=prelude)

    def fetch_fallback_candidate_ids(self) -> list[str]:
        # 没有证据时保持空结果，不能拿任意资料填满候选。
        return []

    def fetch_rows_by_ids(self, ids: Sequence[str]) -> list:
        if not ids:
            return []
        with get_conn() as conn:
            join = "JOIN rag_release_nodes r ON r.node_id=c.node_id" if self.release_id else ""
            version = "r.release_id=%s AND " if self.release_id else ""
            version_args = (self.release_id,) if self.release_id else ()
            return conn.execute(
                f"SELECT c.node_id AS id, c.text AS content, c.metadata_->>'source_path' AS source_path, "
                f"c.metadata_->>'section' AS section, c.metadata_->>'title' AS title, "
                f"c.metadata_::text AS metadata_json, c.embedding::text AS embedding_json "
                f"FROM {self.table} c {join} "
                f"WHERE {version}c.node_id IN ({','.join('%s' for _ in ids)})",
                (*version_args, *ids),
            ).fetchall()

    def get_chunk(self, chunk_id: str):
        from ..services.llama_index_service import _llama_source_sync
        return _llama_source_sync(chunk_id)
