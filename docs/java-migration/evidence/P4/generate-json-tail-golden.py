"""仅用Python json.loads提取完整文档拒绝形状；不导入Java或改写既有黄金。"""
import json
from pathlib import Path

prefix = json.dumps({"title": "DNS😀", "body": "公开教学材料", "status": "published", "shared_confirmed": True},
                    ensure_ascii=False, separators=(",", ":"))
cases = []
for name, suffix in [("second_object", "{}"), ("garbage", "oops"),
                     ("after_whitespace", " \r\n\t{}"), ("scalar_tail", " 1")]:
    raw = prefix + suffix
    try:
        json.loads(raw)
        raise AssertionError("must reject")
    except json.JSONDecodeError as error:
        detail = [{"type": "json_invalid", "loc": ["body", error.pos], "msg": "JSON decode error",
                   "input": {}, "ctx": {"error": error.msg}}]
    cases.append({"name": name, "raw": raw, "detail": detail})
root = Path(__file__).resolve().parents[4]
target = root / "backend-java/src/test/resources/migration/J4-02/json-trailing-golden.json"
target.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
