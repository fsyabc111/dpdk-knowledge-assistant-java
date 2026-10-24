import asyncio

from fastapi import APIRouter, Depends, HTTPException

from ..core.auth import Principal, require_auth
from ..storage.retrieval_store import get_historical_chunk

router = APIRouter(prefix="/api", tags=["sources"])


@router.get("/sources/{chunk_id}")
async def get_source(chunk_id: str, principal: Principal = Depends(require_auth)):
    if chunk_id.startswith("li_"):
        from ..services.llama_index_service import get_llama_index_source
        chunk = await get_llama_index_source(chunk_id)
    else:
        # 历史引用回读：legacy 时期写入的 knowledge_chunks 行仅用于还原旧回答的来源。
        chunk = await asyncio.to_thread(get_historical_chunk, chunk_id)
    if not chunk:
        raise HTTPException(status_code=404, detail="source chunk not found")
    return chunk
