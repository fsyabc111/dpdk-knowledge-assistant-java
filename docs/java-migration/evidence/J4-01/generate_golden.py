"""只导入冻结 Python 切块纯函数，生成合成跨语言黄金；不建库/联网。"""
from __future__ import annotations
import hashlib
import json
import sys
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "backend"))
from app.services.knowledge_sources import RegexSourceChunker

cases = [
    ("markdown", "docs/a.md", "# 中文\n\n正文🙂e\u0301é\n## 二级\n第二段\n"),
    ("headers", "a.md", "前言\n#\n\n## 标题\n#尾节\n"),
    ("empty", "a.md", ""),
    ("whitespace", "a.md", "\u0085\u001c\u001d\u001e\u001f\u00a0 \t"),
    ("linebreaks", "a.md", "# 标题\r\n甲\r乙\n丙\v丁\f戊\x1c己\x1d庚\x1e辛\x85壬\u2028癸\u2029尾\n"),
    ("exact2600", "a.md", "🙂" * 2600),
    ("over2600", "a.md", "🙂" * 2601),
    ("longparagraphs", "a.md", "# 长\n\n" + "甲" * 1800 + "\n \t\n" + "乙" * 1600),
    ("repeatparagraphs", "a.md", "相同\n\n" * 900),
    ("nfc", "a.md", "é" * 2601),
    ("nfd", "a.md", "e\u0301" * 1400),
    ("code", "src/a.c", "/* 前言 */\n#include <x.h>\nstatic inline int parse_dns(void) {\n  return 1;\n}\ntypedef struct item {\n int x;\n} item;\nint after(void) { return 2; }\n"),
    ("unicodefunction", "源.c", "int 函数名(void) {\n  return 3;\n}\nint f_é(void) {return 4;}\n"),
    ("functionblankprefix", "a.c", "\n\nint f(void) {return 1;}\n\nint g(void) {return 2;}\n"),
    ("anonymousstruct", "a.h", "typedef struct {\n int x;\n} a;\ntypedef struct named { int y; } b;\n"),
    ("no_code_match", "a.py", "def f():\n    return '🙂'\n"),
    ("longcode", "a.c", "int f(void) {\n" + ("x++;🙂\n" * 700) + "}\n"),
    ("yaml", "a.YAML", "# 配置\nkey: value\n# 后段\nother: 🙂\n"),
    ("single_hash", "a.md", "#"),
    ("longline", "a.js", "x" * 5201),
    ("crlfcode", "a.c", "int f(void) {\r\n return 1;\r\n}\r\nint g(void) { return 2;}\r\n"),
]
chunker = RegexSourceChunker()
output = []
for name, path, raw in cases:
    # Path.read_text 的 universal newlines；此处显式模拟，避免使用外部文件。
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    chunks = chunker.chunk(text, path, Path(path))
    output.append({"case": name, "path": path, "input": raw,
                   "chunks": [asdict(c) for c in chunks]})
fixture = {"source": "backend/app/services/knowledge_sources.py", "cases": output}
fixture["source_sha256"] = hashlib.sha256((ROOT / fixture["source"]).read_bytes()).hexdigest()
target = ROOT / "backend-java/src/test/resources/migration/J4-01/chunker-golden.json"
target.write_text(json.dumps(fixture, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"cases": len(output), "chunks": sum(len(c["chunks"]) for c in output),
                  "fixture_sha256": hashlib.sha256(target.read_bytes()).hexdigest()}))

# 完整 snapshot_sources 仍调用实际 YAML/文件/切块纯源路径，数据库仅注入 published 行。
from contextlib import contextmanager
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch
from app.services import rag_index_service as index
source_yaml = """sources:
  - name: docs
    root: ${DPDK_REPO_PATH}
    include: [a, a-z.md, nested, '\ue000.md', '🙂.md']
  - name: duplicate
    root: ${DPDK_REPO_PATH}
    include: [nested]
  - name: inner
    root: ${DPDK_REPO_PATH}/nested
    include: [function.c]
  - name: knowledge_base
    root: ${ASSISTANT_PROJECT_PATH}
    include: [knowledge]
"""
files = {"repo/a/b.md": "# 中文\r\n正文🙂\r尾\n", "repo/a-z.md": "é e\u0301",
         "repo/nested/function.c": "/* 前言 */\nint parse_dns(void) {\n return 1;\n}\n",
         "repo/\ue000.md": "BMP", "repo/🙂.md": "ASTRAL", "assistant/knowledge/a.yaml": "# 配置\nkey: value\n"}
published = [{"id": "a", "title": "发布甲", "body": "正文🙂", "revision": 2},
             {"id": "b", "title": "发布乙", "body": "# 内嵌\n内容", "revision": 1}]
class Rows:
    def execute(self, sql):
        assert "status='published' ORDER BY id" in sql
        return self
    def fetchall(self): return published
@contextmanager
def fake_db(): yield Rows()
with TemporaryDirectory(prefix="j401-oracle-") as directory:
    root = Path(directory)
    for path, text in files.items():
        target_file = root / path
        target_file.parent.mkdir(parents=True, exist_ok=True)
        target_file.write_text(text, encoding="utf-8", newline="")
    (root / "sources.yml").write_text(source_yaml, encoding="utf-8")
    settings = SimpleNamespace(knowledge_config=root / "sources.yml", dpdk_repo_path=root / "repo",
                               assistant_project_path=root / "assistant", embedding_model_name="合成-model",
                               embedding_api_base="http://127.0.0.1:1/v1")
    with patch("app.services.knowledge_sources.get_settings", return_value=settings), patch.object(index, "get_conn", fake_db):
        snapshot = index.snapshot_sources(settings)
    snapshot_fixture = {"source_yaml": source_yaml, "files": files, "published": published, "expected": snapshot,
                        "configuration": index.index_configuration(settings)}
snapshot_target = ROOT / "backend-java/src/test/resources/migration/J4-01/snapshot-golden.json"
snapshot_target.write_text(json.dumps(snapshot_fixture, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"snapshot_nodes": len(snapshot), "snapshot_sha256": hashlib.sha256(snapshot_target.read_bytes()).hexdigest()}))
