"""版本化索引（rag_index_service.snapshot_sources）的知识源加载与切块工具。

S4 收敛后本模块只保留共享的「读知识源 → 切块」纯解析件；legacy
``knowledge_chunks`` 数据面的写入服务（IngestionService）与
scripts/ingest_repo.py 已随旧检索引擎删除——唯一索引入口是
``scripts/ingest_llama_index.py``（LlamaIndex 版本化数据面）。
"""
import re
from pathlib import Path
from typing import Dict, Iterable, List

import yaml

from ..config import get_settings
from ..rag.types import SourceChunk

TEXT_EXTENSIONS = {".md", ".c", ".h", ".cpp", ".hpp", ".py", ".js", ".jsx", ".ts", ".tsx", ".yml", ".yaml"}


def chunk_markdown(text: str, rel_path: str) -> List[SourceChunk]:
    chunks: List[SourceChunk] = []
    current_title = rel_path
    current: List[str] = []
    for line in text.splitlines():
        if line.startswith("#") and current:
            content = "\n".join(current).strip()
            if content:
                chunks.extend(split_long_chunk(current_title, content, {"source_type": "doc"}))
            current = []
        if line.startswith("#"):
            current_title = line.lstrip("#").strip() or rel_path
        current.append(line)
    content = "\n".join(current).strip()
    if content:
        chunks.extend(split_long_chunk(current_title, content, {"source_type": "doc"}))
    return chunks


FUNC_RE = re.compile(r"^\s*(?:static\s+)?(?:inline\s+)?[A-Za-z_][\w\s\*\(\)]{1,120}\s+([A-Za-z_]\w*)\s*\([^;]*\)\s*\{", re.M)
STRUCT_RE = re.compile(r"^\s*typedef\s+struct\s+([A-Za-z_]\w*)?", re.M)


def chunk_code(text: str, rel_path: str) -> List[SourceChunk]:
    lines = text.splitlines()
    starts = []
    for match in FUNC_RE.finditer(text):
        starts.append((text[: match.start()].count("\n"), match.group(1)))
    for match in STRUCT_RE.finditer(text):
        starts.append((text[: match.start()].count("\n"), match.group(1) or "typedef struct"))
    starts.sort()
    if not starts:
        return split_long_chunk(rel_path, text, {"source_type": "code", "language": Path(rel_path).suffix.lstrip(".")})

    chunks: List[SourceChunk] = []
    if starts[0][0] > 0:
        chunks.extend(split_long_chunk(rel_path, "\n".join(lines[:starts[0][0]]),
                                       {"source_type": "code", "start_line": "1"}))
    for index, (start_line, symbol) in enumerate(starts):
        end_line = starts[index + 1][0] if index + 1 < len(starts) else len(lines)
        content = "\n".join(lines[start_line:end_line]).strip()
        if content:
            chunks.extend(
                split_long_chunk(
                    symbol,
                    content,
                    {"source_type": "code", "symbol": symbol, "language": Path(rel_path).suffix.lstrip("."),
                     "start_line": str(start_line + 1), "end_line": str(end_line)},
                )
            )
    return chunks


def split_long_chunk(section: str, content: str, metadata: Dict[str, str], max_chars: int = 2600) -> List[SourceChunk]:
    if len(content) <= max_chars:
        return [SourceChunk(section=section, content=content, metadata=metadata)]
    chunks: List[SourceChunk] = []
    paragraphs = re.split(r"\n\s*\n", content)
    buf: List[str] = []
    for paragraph in paragraphs:
        candidate = "\n\n".join(buf + [paragraph])
        if len(candidate) > max_chars and buf:
            chunks.append(SourceChunk(section=section, content="\n\n".join(buf).strip(), metadata=metadata))
            buf = [paragraph]
        else:
            buf.append(paragraph)
    if buf:
        chunks.append(SourceChunk(section=section, content="\n\n".join(buf).strip(), metadata=metadata))
    # 无空行的长函数/压缩文件也必须受限；保留源码顺序与原始行号。
    bounded = []
    for chunk in chunks:
        for start in range(0, len(chunk.content), max_chars):
            part = chunk.content[start:start + max_chars]
            line = int(metadata.get("start_line", "1")) + text_line_offset(content, chunk.content, start)
            bounded.append(SourceChunk(section=section, content=part, metadata={
                **metadata, "start_line": str(line), "end_line": str(line + part.count("\n")),
            }))
    return bounded


def text_line_offset(content: str, part: str, offset: int) -> int:
    return content[:max(0, content.find(part)) + offset].count("\n")


class YamlKnowledgeSource:
    def __init__(self) -> None:
        self.settings = get_settings()

    def load_config(self) -> Dict:
        path = self.settings.knowledge_config
        if not path.is_absolute():
            path = Path.cwd() / path
        if not path.exists():
            raise FileNotFoundError(f"Knowledge config not found: {path}")
        raw = path.read_text(encoding="utf-8")
        placeholders = {
            "${DPDK_REPO_PATH}": str(self.settings.dpdk_repo_path),
            "${ASSISTANT_PROJECT_PATH}": str(self.settings.assistant_project_path),
        }
        for placeholder, value in placeholders.items():
            raw = raw.replace(placeholder, value)
        return yaml.safe_load(raw) or {}

    def iter_files(self) -> Iterable[Path]:
        config = self.load_config()
        for source in config.get("sources", []):
            root = Path(source["root"]).expanduser().resolve()
            for item in source.get("include", []):
                path = (root / item).resolve()
                if not path.is_relative_to(root):
                    raise ValueError(f"知识源路径越界: {item}")
                if path.is_file() and path.suffix.lower() in TEXT_EXTENSIONS:
                    yield path
                elif path.is_dir():
                    for child in path.rglob("*"):
                        if child.is_file() and child.suffix.lower() in TEXT_EXTENSIONS:
                            resolved = child.resolve()
                            if not resolved.is_relative_to(root):
                                raise ValueError(f"知识源符号链接越界: {child.name}")
                            yield resolved
                else:
                    # macOS commonly masks case mistakes; Linux does not. Fail
                    # the index job instead of silently dropping a source tree.
                    raise FileNotFoundError(f"Knowledge source path not found: {path}")


class RegexSourceChunker:
    def chunk(self, text: str, rel_path: str, path: Path) -> List[SourceChunk]:
        if path.suffix.lower() in {".md", ".yml", ".yaml"}:
            return chunk_markdown(text, rel_path)
        return chunk_code(text, rel_path)
