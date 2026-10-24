import asyncio
import json
import logging

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from prometheus_fastapi_instrumentator import Instrumentator

from .api import admin, assignments, auth, chat, grading, rag, sources, tasks, templates, usage
from .config import get_settings
from .core.llm_client import OpenAICompatibleClient
from .core.logging_config import setup_logging
from .db import close_database_provider, init_db
from .rag.providers import clear_model_caches

settings = get_settings()
setup_logging(settings)

app = FastAPI(title="DPDK Knowledge Assistant", version="0.1.0")
logger = logging.getLogger("assistant")

# HTTP-level Prometheus metrics. /metrics is intentionally app-internal: in
# production nginx only reverse-proxies /api/, so it stays unreachable from
# outside. Do not add it to the proxy config.
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE"],
    allow_headers=["Content-Type", "Authorization", "X-Maintenance-Key"],
)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = "default-src 'self'"
    return response


@app.on_event("startup")
def on_startup() -> None:
    init_db()
    # 唯一检索数据面：固定预热 li 运行时（内部对非 PG 后端跳过）。
    from .services.llama_index_service import warm_llama_index_runtime
    warm_llama_index_runtime()


@app.on_event("shutdown")
async def on_shutdown() -> None:
    from .services.llama_index_service import close_llama_index_runtime
    await close_llama_index_runtime()
    clear_model_caches()
    await OpenAICompatibleClient.close_clients()
    close_database_provider()


@app.get("/api/health")
def health():
    return {"ok": True, "app": "knowledge-assistant"}


@app.get("/api/ready", include_in_schema=False)
async def ready():
    from .db import get_conn
    from .services.rag_index_service import active_release, release_health

    def check():
        with get_conn() as conn:
            conn.execute("SELECT 1").fetchone()
        # 产品路径是唯一检索路径：没有健康可用的活动发布即未就绪（fail-closed）。
        settings = get_settings()
        release = active_release()
        if not release:
            raise ValueError("no active index")
        health = release_health(release, settings)
        if health["declared"] < 1:
            raise ValueError("no active index")
        # 不只信发布记录声明的节点数：成员行与物理节点必须实际齐备。
        if health["members"] != health["declared"] or health["physical"] != health["members"]:
            raise ValueError(
                f"index nodes incomplete: declared={health['declared']} "
                f"members={health['members']} physical={health['physical']}")
        if not health["config_match"]:
            raise ValueError("index configuration mismatch with current embedding settings")
    try:
        await asyncio.wait_for(asyncio.to_thread(check), 5)
    except Exception as exc:
        logger.warning(json.dumps({"event": "ready_check_failed", "reason": str(exc)[:200]},
                                  ensure_ascii=False))
        raise HTTPException(503, "not ready") from None
    return {"ok": True}


_rag_maintenance_task = None
_task_heartbeat_task = None


@app.on_event("startup")
async def start_rag_maintenance():
    global _rag_maintenance_task, _task_heartbeat_task
    from .services import task_service
    # 启动先跑一拍恢复：进程重启留下的孤儿 pending 行（已过 grace 的）即刻重驱动。
    try:
        await task_service.recover_orphans()
    except Exception:
        logger.exception("启动时孤儿任务恢复失败")

    async def maintain():
        from .services.rag_issue_service import RagIssueService
        while True:
            try:
                await asyncio.to_thread(RagIssueService().maintain, get_settings().rag_retention_days)
            except Exception:
                logging.getLogger("assistant").exception("RAG 留存维护失败")
            # 恢复扫描与留存清理独立成败：一边挂了另一边照常跑。
            try:
                await task_service.recover_orphans()
            except Exception:
                logging.getLogger("assistant").exception("孤儿任务恢复扫描失败")
            await asyncio.sleep(300)
    _rag_maintenance_task = asyncio.create_task(maintain())
    # 在跑任务属主心跳：多副本共享库时防止其他副本把本进程的任务当孤儿双执行。
    _task_heartbeat_task = asyncio.create_task(task_service.heartbeat_loop())


@app.on_event("shutdown")
async def stop_rag_maintenance():
    if _rag_maintenance_task is not None:
        _rag_maintenance_task.cancel()
        await asyncio.gather(_rag_maintenance_task, return_exceptions=True)
    if _task_heartbeat_task is not None:
        _task_heartbeat_task.cancel()
        await asyncio.gather(_task_heartbeat_task, return_exceptions=True)


app.include_router(chat.router)
app.include_router(auth.router)
app.include_router(admin.router)
app.include_router(sources.router)
app.include_router(templates.router)
app.include_router(usage.router)
app.include_router(grading.router)
app.include_router(assignments.router)
app.include_router(rag.router)
app.include_router(tasks.router)
