from dataclasses import dataclass
from typing import Any, Dict


@dataclass
class SourceChunk:
    section: str
    content: str
    metadata: Dict[str, str]


@dataclass
class RetrievedChunk:
    id: str
    source_path: str
    title: str
    section: str
    content: str
    metadata: Dict[str, Any]
    score: float


@dataclass
class AnswerDraft:
    answer: str
    confidence: str
