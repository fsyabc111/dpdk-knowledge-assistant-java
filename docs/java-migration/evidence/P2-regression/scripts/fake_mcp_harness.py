#!/usr/bin/env python3
"""P2 回归专用 fake MCP harness（评测替身，stdlib 实现）。

实现 teaching_harness 的 MCP Streamable HTTP 最小形态：
  POST /mcp
    initialize                -> JSON-RPC result（带 Mcp-Session-Id）
    notifications/initialized -> 200 {}
    tools/call grade_plugin   -> JSON-RPC result（structuredContent=评测负载）

行为与纪律：
  - 评测负载默认 {"ok":true,"protocol":..., "score":80,"passed":4,"cases_total":5,...}；
    若控制文件存在（--payload-file），逐请求读取并整体作为 structuredContent（可动态改成绩）。
  - 只记录调用计数与参数指纹（protocol、plugin_source 长度与 sha256 前 12 位），
    绝不落盘、不打印 plugin_source 原文（敏感纪律：账本只存身份/计数）。
  - 状态写入 --state-file（JSON：initialize 计数、tools/call 计数、指纹列表）。
"""
import argparse
import hashlib
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
STATE = {"initializes": 0, "tool_calls": 0, "initialized_notifications": 0, "calls": []}

DEFAULT_PAYLOAD = {
    "ok": True,
    "protocol": "dns",
    "score": 80,
    "passed": 4,
    "cases_total": 5,
    "failed": 1,
    "crashed": 0,
    "timedout": 0,
    "cheat": 0,
    "results": [],
}


def load_payload(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    return None


def persist(path):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(STATE, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 抑制默认访问日志（避免噪音）
        pass

    def do_POST(self):
        if self.path.rstrip("/") != "/mcp":
            self._respond(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._respond(400, {"error": "bad json"})
            return
        method = req.get("method", "")
        req_id = req.get("id")
        session = self.headers.get("Mcp-Session-Id")

        if method == "initialize":
            with LOCK:
                STATE["initializes"] += 1
                n = STATE["initializes"]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Mcp-Session-Id", "fake-session-%d" % n)
            body = json.dumps({
                "jsonrpc": "2.0", "id": req_id,
                "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "serverInfo": {"name": "fake-teaching-harness", "version": "0.2.0"},
                },
            }).encode("utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif method == "notifications/initialized":
            with LOCK:
                STATE["initialized_notifications"] += 1
                persist(ARGS.state_file)
            self._respond(200, {"jsonrpc": "2.0"})
        elif method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args_ = params.get("arguments") or {}
            protocol = args_.get("protocol")
            source = args_.get("plugin_source") or ""
            fingerprint = {
                "tool": name,
                "protocol": protocol,
                "plugin_source_len": len(source),
                "plugin_source_sha12": hashlib.sha256(source.encode("utf-8")).hexdigest()[:12],
                "at": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()),
            }
            if name == "grade_plugin":
                payload = load_payload(ARGS.payload_file) if ARGS.payload_file else None
                if payload is None:
                    payload = dict(DEFAULT_PAYLOAD)
                    if isinstance(protocol, str):
                        payload["protocol"] = protocol
                result = {"structuredContent": payload, "isError": False}
            else:
                result = {"isError": True,
                          "content": [{"type": "text", "text": "unknown tool"}]}
            with LOCK:
                STATE["tool_calls"] += 1
                STATE["calls"].append(fingerprint)
                persist(ARGS.state_file)
            self._respond(200, {"jsonrpc": "2.0", "id": req_id, "result": result})
        else:
            self._respond(400, {"jsonrpc": "2.0", "id": req_id,
                                "error": {"code": -32601, "message": "unknown method"}})

    def do_GET(self):
        # 健康探针：不泄露内部状态细节
        self._respond(200, {"ok": True, "service": "fake-mcp-harness"})

    def _respond(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8100)
    ap.add_argument("--state-file", default="/tmp/p2reg/harness_state.json")
    ap.add_argument("--payload-file", default="/tmp/p2reg/grade_payload.json")
    ARGS = ap.parse_args()
    os.makedirs(os.path.dirname(ARGS.state_file), exist_ok=True)
    persist(ARGS.state_file)
    server = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    print("fake-mcp-harness listening on http://%s:%d/mcp" % (ARGS.host, ARGS.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
