"""引用编号与提供给模型的片段顺序保持一一对应。"""
from typing import List, Sequence

from ..rag.types import RetrievedChunk
from ..schemas.chat import Citation


def citations_from_chunks(chunks: Sequence[RetrievedChunk]) -> List[Citation]:
    """保留全部片段及原序；同文件的不同片段也各占一个编号。"""
    return [
        Citation(
            chunk_id=chunk.id, source_path=chunk.source_path, title=chunk.title or chunk.source_path,
            section=chunk.section, score=round(chunk.score, 4),
        )
        for chunk in chunks
    ]
