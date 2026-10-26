"""从冻结Python契约生成Java静态文档资源，先验证所有HTML黄金再写入。"""
import hashlib
import json
from pathlib import Path
from fastapi.openapi.docs import get_redoc_html, get_swagger_ui_html, get_swagger_ui_oauth2_redirect_html

root = Path(__file__).resolve().parents[2]
golden = json.loads((root / "migration/fixtures/p0-v2/python-golden.json").read_text())
observations = {row["case_id"]: row for row in golden["observations"]}
title = "DPDK Knowledge Assistant"
assets = {
    "docs.html": ("/docs", get_swagger_ui_html(openapi_url="/openapi.json", title=title + " - Swagger UI",
                oauth2_redirect_url="/docs/oauth2-redirect").body),
    "oauth2-redirect.html": ("/docs/oauth2-redirect", get_swagger_ui_oauth2_redirect_html().body),
    "redoc.html": ("/redoc", get_redoc_html(openapi_url="/openapi.json", title=title + " - ReDoc").body),
}
for name, (route, data) in assets.items():
    expected = observations[f"GET {route}::success"]["body"]
    assert hashlib.sha256(data).hexdigest() == expected["sha256"], (name, "frozen HTML hash mismatch")
    assert len(data.decode()) == expected["length"], (name, "frozen HTML length mismatch")
target = root / "backend-java/src/main/resources/compat/frozen-docs"
target.mkdir(parents=True, exist_ok=True)
for name, (_, data) in assets.items():
    (target / name).write_bytes(data)
spec = observations["GET /openapi.json::success"]["body"]
(target / "openapi.json").write_text(json.dumps(spec, ensure_ascii=False, separators=(",", ":")))
print("frozen_docs_generated", len(assets) + 1)
