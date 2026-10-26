"""冻结Python副本的独立TCP服务。只替换模型/embedding/MCP，检索SQL真实。"""
from __future__ import annotations

import asyncio
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import socket
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))


def configure():
    # 只能从共享隔离manifest连接本轮owned库；任何app导入都发生在配置清白之后。
    from acceptance.contract.isolation import PgIsolation, OwnedDatabase
    identity_keys = tuple(PgIsolation.REQUIRED)
    required = identity_keys + ("J5_MANIFEST", "J5_OWNERSHIP", "J5_DB_NAME", "J5_CONTROL_KEY", "J5_SOURCE")
    missing = [k for k in required if k not in os.environ]
    if missing:
        raise RuntimeError("缺少显式配置:" + ",".join(missing))
    incoming = {k: v for k, v in os.environ.items() if k in required or k.startswith("J5_")}
    keep = {k: v for k, v in os.environ.items() if k in {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL"}}
    os.environ.clear()
    os.environ.update(keep)
    os.environ.update(incoming)
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
    isolation = PgIsolation.from_env(incoming["J5_OWNERSHIP"], allowed_prefixes=("p5_capacity_", "p5_resilience_"))
    recorded = isolation.manifest["owned"].get(incoming["J5_DB_NAME"])
    if recorded is None:
        raise RuntimeError("目标库不在本轮manifest")
    db = OwnedDatabase(**{k: recorded[k] for k in ("name", "url", "comment", "round_id")})
    isolation.verify_owned(db)
    from urllib.parse import quote
    dsn = f"postgresql://{quote(isolation.user)}:{quote(isolation.password)}@{isolation.host}:{isolation.port}/{db.name}"
    os.environ.update({"DATABASE_BACKEND": "postgres", "DATABASE_URL": dsn, "AUTH_MODE": "account",
        "BCRYPT_ROUNDS": "12", "DATABASE_POOL_MAX_SIZE": "32", "GRADING_DAILY_QUOTA": "20",
        "RATE_LIMIT_CHAT_PER_MINUTE": "20", "LLM_DAILY_QUOTA_DEFAULT": "200",
        "RAG_MAX_ACTIVE_REQUESTS": "20", "RAG_MAX_WAITING_REQUESTS": "20", "RAG_QUEUE_TIMEOUT_SEC": "10",
        "RAG_TASK_MAX_SECONDS": "600", "RAG_TASK_INPUT_TTL_HOURS": "24", "RAG_ONLINE_JUDGE_ENABLED": "false",
        "LLM_API_BASE": "http://127.0.0.1:9/v1", "LLM_API_KEY": "j5-synthetic-not-secret", "MODEL_NAME": "j5-fixed-model",
        "EMBEDDING_API_BASE": "http://127.0.0.1:9/v1", "EMBEDDING_API_KEY": "j5-synthetic-not-secret",
        "EMBEDDING_MODEL_NAME": "j5-fixed-embedding", "HARNESS_MCP_URL": "http://127.0.0.1:9/mcp",
        "LOG_LEVEL": "ERROR", "LOG_FILE": "", "ASSISTANT_PROJECT_PATH": str(ROOT),
        "DPDK_REPO_PATH": str(ROOT / ".migration-artifacts/j5/no-external-repo"),
        "KNOWLEDGE_CONFIG": str(ROOT / "migration/fixtures/p0-v1/knowledge.yml")})
    sys.path.insert(0, str(ROOT / "backend"))
    from app.config import Settings, get_settings
    Settings.model_config["env_file"] = None
    get_settings.cache_clear()
    settings = Settings(_env_file=None)
    get_settings.cache_clear()
    if get_settings().database_url != dsn or settings.bcrypt_rounds != 12:
        raise RuntimeError("Python配置未隔离")
    # 监听由Uvicorn管理；连接仅PG，客户端不许访问dead上游或其他loopback服务。
    original, original_ex = socket.socket.connect, socket.socket.connect_ex
    def check(address):
        if isinstance(address, tuple) and not (address[0] in {isolation.host, "127.0.0.1", "::1"} and address[1] == isolation.port):
            raise RuntimeError("J5 Python禁止非专用PG出站连接")
    def connect(sock, address):
        check(address)
        return original(sock, address)
    def connect_ex(sock, address):
        check(address)
        return original_ex(sock, address)
    socket.socket.connect, socket.socket.connect_ex = connect, connect_ex
    return isolation, db


def schema(isolation, db):
    # 只在新空owned库执行冻结Alembic；不导入会清P0库的旧性能Runtime。
    isolation.verify_owned(db)
    with isolation._connect(db.name) as conn:
        if conn.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'").fetchone()[0]:
            raise RuntimeError("Pythonserver仅接受新空owned库")
    from app.db import init_db
    init_db()
    from alembic import command
    from alembic.config import Config
    from unittest.mock import patch
    import sqlalchemy
    config = Config(str(ROOT / "backend/alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "backend/alembic"))
    original = sqlalchemy.engine_from_config
    def engine(section, *args, **kwargs):
        section = dict(section)
        section["sqlalchemy.url"] = section["sqlalchemy.url"].replace("postgresql://", "postgresql+psycopg://", 1)
        return original(section, *args, **kwargs)
    with patch("sqlalchemy.engine_from_config", engine):
        command.upgrade(config, "head")
    init_db()


class Boundaries:
    def __init__(self):
        self.calls = Counter()
        self.hold = False
        self.model_delay = float(os.environ.get("J5_MODEL_DELAY", "1"))
        self.retrieval_delay = float(os.environ.get("J5_RETRIEVAL_DELAY", ".05"))
        self.mcp_delay = float(os.environ.get("J5_MCP_DELAY", ".2"))

    def install(self):
        from langchain_core.language_models.chat_models import BaseChatModel
        from langchain_core.messages import AIMessage, ToolMessage
        from langchain_core.outputs import ChatGeneration, ChatResult
        from app.agent import service as agent
        from app.agent.mcp_client import HarnessMcpClient
        from app.services import retrieval_service, llama_index_service, conversation_memory
        state = self
        class Model(BaseChatModel):
            @property
            def _llm_type(self):
                return "j5-fixed-local-script"
            def bind_tools(self, tools, **kwargs):
                return self
            def _generate(self, messages, **kwargs):
                raise RuntimeError("禁止同步外部模型出口")
            async def _agenerate(self, messages, **kwargs):
                state.calls["model"] += 1
                while state.hold:
                    await asyncio.sleep(.01)
                await asyncio.sleep(state.model_delay)
                if isinstance(messages[-1], ToolMessage):
                    content = "合成评测已完成。" if messages[-1].name == "grade_plugin" else "合成 DNS 标签按长度编码[1]。"
                    message = AIMessage(content=content, usage_metadata={"input_tokens": 32, "output_tokens": 12, "total_tokens": 44})
                else:
                    question = next((str(m.content) for m in reversed(messages) if m.type == "human"), "")
                    tool = "grade_plugin" if "__grade__" in question else "kb_search"
                    args = {"protocol": "dns", "plugin_source": os.environ["J5_SOURCE"]} if tool == "grade_plugin" else {"query": "合成DNS标签"}
                    message = AIMessage(content="", tool_calls=[{"name": tool, "args": args, "id": "j5-call-1", "type": "tool_call"}],
                                        usage_metadata={"input_tokens": 32, "output_tokens": 12, "total_tokens": 44})
                return ChatResult(generations=[ChatGeneration(message=message)])
        class Embeddings:
            async def embed(self, texts, **kwargs):
                state.calls["embedding"] += 1
                # 50ms仅属于真实外部向量调用；Python原始缓存命中不额外补睡眠。
                await asyncio.sleep(state.retrieval_delay)
                return [[1.0] + [0.0] * 1023 for _ in texts]
        class Summary:
            async def generate(self, messages, **kwargs):
                state.calls["summary_model"] += 1
                await asyncio.sleep(state.model_delay)
                return "合成摘要"
        agent.build_agent_chat_model = lambda settings: Model()
        conversation_memory.build_chat_model = lambda settings: Summary()
        retrieval_service.build_embedding_model = lambda settings: Embeddings()
        # HybridDpdkRetriever/LiNodeRetrievalStore、rewrite/embedding缓存与SQL均原样保留。
        # warm仅创建外部LlamaIndex embedding客户端；真实startup/维护/心跳全部保留。
        llama_index_service.warm_llama_index_runtime = lambda: None
        async def mcp(_client, tool, arguments):
            state.calls["mcp:" + tool] += 1
            await asyncio.sleep(state.mcp_delay)
            if tool == "list_protocols":
                return {"ok": True, "protocols": [{"name": "dns", "cases": 2, "categories": {"basic": 2}}]}
            if tool == "get_reference_answer":
                return {"ok": False, "error": "no_reference_in_synthetic"}
            return {"ok": True, "protocol": "dns", "score": 100, "passed": 2, "cases_total": 2,
                    "failed": 0, "crashed": 0, "timedout": 0, "cheat": 0,
                    "results": [{"name": "synthetic_1", "result": "PASS"}, {"name": "synthetic_2", "result": "PASS"}]}
        HarnessMcpClient.call_tool = mcp


async def seed():
    from app.services.user_service import UserService
    from app.services.assignment_service import AssignmentService
    from app.services.rag_index_service import index_configuration
    from app.config import get_settings
    from app.db import get_conn
    users = UserService()
    leader = "U20990000999"
    students = [f"U2099000{i:04d}" for i in range(1, 61)]
    assert len((await users.create_users([leader], roles=["leader"], daily_quota=100000))["created"]) == 1
    assert len((await users.create_users(students, roles=["viewer"], daily_quota=100000, created_by=leader))["created"]) == 60
    assignment = await AssignmentService().create_assignment(actor_id=leader, actor_roles=["leader"],
        title="P0合成性能作业", description="运行时合成", deadline=datetime.now(timezone.utc) + timedelta(days=1),
        items=[{"protocol": "dns", "points": 100}])
    tokens = [(await users.issue_token(user))[0] for user in students]
    with get_conn() as conn:
        conn.execute("INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)",
            ("j5-perf-kb-0001", "合成 DNS 标签按长度编码。", json.dumps({"source_path": "synthetic/dns.md", "title": "合成DNS", "section": "标签"}),
             "[1," + ",".join(["0"] * 1023) + "]"))
        conn.execute("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,1)",
            ("j5-fixed-release", index_configuration(get_settings()), datetime.now(timezone.utc).isoformat()))
        conn.execute("INSERT INTO rag_release_nodes(release_id,node_id) VALUES ('j5-fixed-release','j5-perf-kb-0001')")
        conn.execute("UPDATE rag_index_state SET release_id='j5-fixed-release' WHERE name='active'")
    return {"students": students, "tokens": tokens, "assignment_id": assignment["id"]}


def main():
    isolation, db = configure()
    schema(isolation, db)
    state = Boundaries()
    state.install()
    from app.main import app
    from fastapi import Header, HTTPException
    def authorize(key):
        import hmac
        if not hmac.compare_digest(key or "", os.environ["J5_CONTROL_KEY"]):
            raise HTTPException(403)
    @app.get("/__j5/state")
    async def status(x_j5_control: str = Header()):
        authorize(x_j5_control)
        from app.services.rag_product_service import admission
        return {"active": admission.active, "waiting": admission.waiting, "calls": dict(state.calls)}
    @app.post("/__j5/control")
    async def control(body: dict, x_j5_control: str = Header()):
        authorize(x_j5_control)
        if "hold" in body:
            state.hold = bool(body["hold"])
        return await status(x_j5_control)
    manifest = asyncio.run(seed())
    import uvicorn
    listener = socket.socket()
    listener.bind(("127.0.0.1", int(os.environ.get("J5_PORT", "0"))))
    manifest.update({"base_url": "http://127.0.0.1:" + str(listener.getsockname()[1]), "pid": os.getpid(), "runtime": "python",
        "comparison_processes": [os.getpid()], "db_name": db.name, "control_key": os.environ["J5_CONTROL_KEY"],
        "settings": {"pool": 32, "bcrypt": 12, "model_delay_seconds": state.model_delay, "retrieval_delay_seconds": state.retrieval_delay,
                     "mcp_delay_seconds": state.mcp_delay, "active": 20, "waiting": 20, "queue_seconds": 10, "task_seconds": 600, "input_ttl_hours": 24,
                     "retrieval_delay_scope": "external_embedding_call_only", "cache_ttl_seconds": 600, "cache_maxsize": 512}})
    # 在startup完成后写ready文件，避免负载先于真实维护/心跳启动。
    async def ready():
        output = Path(os.environ["J5_MANIFEST"])
        output.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as handle:
            json.dump(manifest, handle, ensure_ascii=False)
    app.add_event_handler("startup", ready)
    config = uvicorn.Config(app, host="127.0.0.1", log_level="error", proxy_headers=True,
                            forwarded_allow_ips="127.0.0.1", access_log=False)
    uvicorn.Server(config).run(sockets=[listener])


if __name__ == "__main__":
    main()
