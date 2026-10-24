from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, Protocol, Sequence

from ..db import get_conn, get_database_provider


class RetrievalStore(Protocol):
    """Dialect-agnostic candidate fetch + chunk lookup interface.

    S4 收敛后生产唯一实现是 ``storage/li_retrieval_store.py`` 的
    ``LiNodeRetrievalStore``（PG-only li 数据面）；``RetrievalService`` 只通过
    本协议访问候选读取，不再存在按 DATABASE_BACKEND 选择的 legacy 存储工厂。
    """

    def fetch_rows_by_ids(self, chunk_ids: Sequence[str]) -> List[Any]: ...

    def fetch_fts_candidate_ids(self, match_query: str, limit: int) -> List[str]: ...

    def fetch_path_candidate_ids(self, fragments: Sequence[str], limit: int) -> List[str]: ...

    def fetch_semantic_candidate_ids(
        self, query_embedding: Sequence[float], limit: int
    ) -> List[str]: ...

    def fetch_fallback_candidate_ids(self) -> List[str]: ...

    def get_chunk(self, chunk_id: str) -> Optional[Dict[str, Any]]: ...


def get_historical_chunk(chunk_id: str) -> Optional[Dict[str, Any]]:
    """历史引用回读：旧 ``knowledge_chunks`` 表的最小直查。

    S4 收敛后检索面只认 ``li_`` 版本化节点；本函数仅服务历史会话里落库的
    非 ``li_`` 引用（``/api/sources`` 与 ``load_session`` 的引用展开），
    不再承担候选召回——表留、路径删。
    """
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        row = conn.execute(
            "SELECT c.id, c.source_path, c.section, c.content, c.metadata_json, d.title "
            "FROM knowledge_chunks c "
            "LEFT JOIN knowledge_documents d ON d.id = c.document_id "
            f"WHERE c.id = {ph}",
            (chunk_id,),
        ).fetchone()
    if not row:
        return None
    metadata_raw = row["metadata_json"] or "{}"
    metadata = metadata_raw if isinstance(metadata_raw, (dict, list)) else json.loads(metadata_raw)
    return {
        "chunk_id": row["id"],
        "source_path": row["source_path"],
        "title": row["title"] or row["source_path"],
        "section": row["section"] or "",
        "content": row["content"],
        "metadata": metadata,
    }
