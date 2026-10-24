from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any, AsyncIterator, Dict, Iterable, List, Optional, Protocol

from .types import AnswerDraft, RetrievedChunk, SourceChunk

if TYPE_CHECKING:  # 仅类型期依赖：interfaces 不能在运行期拖入 core.usage
    from ..core.usage import UsageRecorder


class ChatModel(Protocol):
    async def generate(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional["UsageRecorder"] = None,
        stage: str = "",
    ) -> str:
        """Generate a chat completion from provider-native message dictionaries.

        ``usage`` 是可选的用量累加器（见 ``app.core.usage``）：实现必须把上游返回的
        usage 记进去，且**不得**因为要记账而改变返回值、异常或重试语义。
        """

    async def stream(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional["UsageRecorder"] = None,
        stage: str = "",
    ) -> AsyncIterator[str]:
        """Yield provider-native incremental text deltas."""


class EmbeddingModel(Protocol):
    async def embed(
        self,
        texts: List[str],
        *,
        usage: Optional["UsageRecorder"] = None,
        stage: str = "",
    ) -> List[List[float]]:
        """Embed one or more texts into dense vectors."""


class QueryRewriter(Protocol):
    async def rewrite(
        self,
        original: str,
        conversation_context: Optional[str] = None,
        *,
        usage: Optional["UsageRecorder"] = None,
        stage: str = "",
    ) -> str:
        """Rewrite a user question into retrieval-friendly keywords.

        实现可选地接受 ``usage`` sink 记账，但**不得**因此改变返回值或异常语义：
        记账是旁路，改写失败仍按既有方式退回原查询。
        """


class KnowledgeRetriever(Protocol):
    async def search(
        self,
        query: str,
        limit: int = 6,
        trace: Optional[Any] = None,
        conversation_context: Optional[str] = None,
    ) -> List[RetrievedChunk]:
        """Return ranked chunks for an end-user query."""

    def get_chunk(self, chunk_id: str) -> Optional[Dict[str, Any]]:
        """Return a stored source chunk by id."""


class Chunker(Protocol):
    def chunk(self, text: str, rel_path: str, path: Path) -> List[SourceChunk]:
        """Split source text into indexable chunks."""


class KnowledgeSource(Protocol):
    def iter_files(self) -> Iterable[Path]:
        """Yield files that should be indexed."""


class AnswerGenerator(Protocol):
    async def generate(
        self,
        question: str,
        chunks: List[RetrievedChunk],
        trace: Optional[Any] = None,
        conversation_memory: Optional[Any] = None,
        retrieval_query: Optional[str] = None,
    ) -> AnswerDraft:
        """Generate a grounded answer from retrieved chunks."""
