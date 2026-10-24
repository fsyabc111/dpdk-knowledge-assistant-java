"""无 hash 的索引快照及原子发布。旧节点只读保留，历史引用不会漂移。"""
from __future__ import annotations

import asyncio
import json
import math
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from ..config import get_settings
from ..core.usage import UsageRecorder
from ..db import get_conn, get_database_provider
from ..rag.providers import build_embedding_model
from .knowledge_sources import RegexSourceChunker, YamlKnowledgeSource
from .usage_service import flush_usage_recorder


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


_SQL_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def data_table_name(settings) -> str:
    """物理节点表名（与 llama_index_service._data_table_name 同规则，避免循环 import）。"""
    configured = settings.llama_index_table
    if not _SQL_IDENTIFIER.fullmatch(configured):
        raise ValueError("LLAMA_INDEX_TABLE must be a PostgreSQL identifier")
    return f"data_{configured}"


def index_configuration(settings) -> str:
    """发布/就绪/回滚共用的配置指纹；指纹变化在 ingest 侧强制全量重嵌入。"""
    return json.dumps({"model": settings.embedding_model_name, "api_base": settings.embedding_api_base,
                       "dimensions": 1024, "chunker": "bounded-lines-v2"}, sort_keys=True)


def active_release() -> str:
    with get_conn() as conn:
        row = conn.execute("SELECT release_id FROM rag_index_state WHERE name='active'").fetchone()
    return row["release_id"] if row else ""


def release_configuration(release_id: str) -> str:
    """单个发布版本的配置指纹（检索路径每实例一次）；版本不存在返回空串。"""
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        row = conn.execute(f"SELECT configuration FROM rag_releases WHERE id={ph}", (release_id,)).fetchone()
    return row["configuration"] if row else ""


def release_health(release_id: str, settings) -> dict:
    """发布版本健康核对：声明节点数 / 成员行数 / 物理节点数 / 配置指纹匹配。"""
    ph = get_database_provider().placeholder
    table = f'"{data_table_name(settings)}"'
    with get_conn() as conn:
        row = conn.execute(f"SELECT node_count, configuration FROM rag_releases WHERE id={ph}", (release_id,)).fetchone()
        if not row:
            return {"declared": 0, "members": 0, "physical": 0, "config_match": False}
        members = conn.execute(f"SELECT COUNT(*) AS c FROM rag_release_nodes WHERE release_id={ph}",
                               (release_id,)).fetchone()["c"]
        physical = conn.execute(
            f"SELECT COUNT(*) AS c FROM rag_release_nodes r JOIN {table} c ON c.node_id=r.node_id "
            f"WHERE r.release_id={ph}", (release_id,)).fetchone()["c"]
    return {"declared": row["node_count"], "members": members, "physical": physical,
            "config_match": row["configuration"] == index_configuration(settings)}


def snapshot_sources(settings) -> list[dict]:
    source = YamlKnowledgeSource()
    config = source.load_config()
    roots = [(item["name"], Path(item["root"]).resolve()) for item in config["sources"]]
    chunks = []
    chunker = RegexSourceChunker()
    for path in sorted(set(source.iter_files())):
        # 来源名区分仓库；相对路径保证换主机后身份不变化。
        name, root = max(((name, root) for name, root in roots if path.is_relative_to(root)),
                         key=lambda pair: len(pair[1].parts))
        relative = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        for ordinal, chunk in enumerate(chunker.chunk(text, relative, path)):
            chunks.append({"content": chunk.content, "metadata": {
                **chunk.metadata, "source_key": f"{name}/{relative}", "source_path": relative,
                "section": chunk.section, "title": chunk.section or relative, "ordinal": ordinal,
            }})
    with get_conn() as conn:
        knowledge = conn.execute("SELECT id,title,body,revision FROM rag_knowledge WHERE status='published' ORDER BY id").fetchall()
    for item in knowledge:
        path = f"knowledge/published/{item['id']}.md"
        for ordinal, chunk in enumerate(chunker.chunk(f"# {item['title']}\n\n{item['body']}", path, Path(path))):
            chunks.append({"content": chunk.content, "metadata": {
                **chunk.metadata, "source_key": path, "source_path": path, "section": chunk.section,
                "title": item["title"], "ordinal": ordinal, "knowledge_id": item["id"],
                "revision": item["revision"],
            }})
    if not chunks:
        raise ValueError("知识源为空，拒绝覆盖活动索引")
    return chunks


def plan_nodes(desired: list[dict], existing: list[dict], same_config: bool) -> tuple[list[dict], list[dict]]:
    by_position = {}
    for node in existing:
        meta = node["metadata"]
        by_position[(meta.get("source_key"), meta.get("ordinal"))] = node
    changed = []
    for node in desired:
        meta = node["metadata"]
        old = by_position.get((meta["source_key"], meta["ordinal"]))
        if same_config and old and old["content"] == node["content"] and old["metadata"] == meta:
            node["id"] = old["id"]
        else:
            node["id"] = "li_" + uuid.uuid4().hex
            changed.append(node)
    return desired, changed


def _existing(table: str, release: str):
    with get_conn() as conn:
        config = conn.execute("SELECT configuration FROM rag_releases WHERE id=%s", (release,)).fetchone()
        rows = conn.execute(
            f"SELECT c.node_id,c.text,c.metadata_ FROM {table} c "
            "JOIN rag_release_nodes r ON c.node_id=r.node_id WHERE r.release_id=%s", (release,),
        ).fetchall()
    return (config["configuration"] if config else ""), [
        {"id": row["node_id"], "content": row["text"], "metadata": row["metadata_"]} for row in rows
    ]


def _publish(table, expected, release, configuration, desired, changed):
    with get_conn() as conn:
        # CAS 发布锁同时阻止两个不同来源快照互相覆盖；整笔事务失败即回滚。
        updated = conn.execute(
            "UPDATE rag_index_state SET release_id=%s WHERE name='active' AND release_id=%s",
            (release, expected),
        ).rowcount
        if updated != 1:
            raise RuntimeError("另一任务已发布新版本，请重新扫描后再试")
        for node in changed:
            vector = "[" + ",".join(str(float(x)) for x in node["embedding"]) + "]"
            conn.execute(
                f"INSERT INTO {table}(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)",
                (node["id"], node["content"], json.dumps(node["metadata"], ensure_ascii=False), vector),
            )
        conn.execute("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,%s)",
                     (release, configuration, now(), len(desired)))
        for node in desired:
            conn.execute("INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)", (release, node["id"]))


async def ingest_release(settings, table: str) -> dict:
    previous = await asyncio.to_thread(active_release)
    desired = await asyncio.to_thread(snapshot_sources, settings)
    configuration = index_configuration(settings)
    old_config, existing = await asyncio.to_thread(_existing, table, previous)
    desired, changed = plan_nodes(desired, existing, old_config == configuration)
    ids = {node["id"] for node in desired}
    removed = sum(node["id"] not in ids for node in existing)
    if not changed and not removed:
        return {"release_id": previous, "nodes": len(desired), "unchanged": len(desired),
                "inserted_or_updated": 0, "deleted": 0, "deduplicated": 0, "table": settings.llama_index_table}
    embedding = build_embedding_model(settings)
    batch_size = max(1, min(settings.embedding_batch_size, 10))
    # 离线摄取没有请求 trace：本轮自建累加器，不管成功失败都在 finally 落库 ——
    # 中途失败时前面几批的 embedding 已经花掉了，账不能跟着异常一起丢。
    usage = UsageRecorder()
    try:
        for start in range(0, len(changed), batch_size):
            batch = changed[start:start + batch_size]
            vectors = await embedding.embed(
                [node["content"] for node in batch], usage=usage, stage="ingest_embed",
            )
            if len(vectors) != len(batch):
                raise ValueError("Embedding 数量与片段不匹配")
            for node, vector in zip(batch, vectors, strict=True):
                if len(vector) != 1024 or not all(math.isfinite(x) for x in vector) or not any(vector):
                    raise ValueError("Embedding 必须是非零的 1024 维有限数值")
                node["embedding"] = vector
    finally:
        flush_usage_recorder(usage)
    release = uuid.uuid4().hex
    await asyncio.to_thread(_publish, table, previous, release, configuration, desired, changed)
    return {"release_id": release, "nodes": len(desired), "unchanged": len(desired) - len(changed),
            "inserted_or_updated": len(changed), "deleted": removed, "deduplicated": 0,
            "table": settings.llama_index_table}


def rollback_release(release_id: str, settings=None) -> None:
    """回滚前强制核对：目标版本必须存在、与当前 embedding 配置匹配且物理节点完整。"""
    settings = settings or get_settings()
    health = release_health(release_id, settings)
    if health["declared"] < 1:
        raise ValueError("版本不存在")
    if not health["config_match"]:
        raise ValueError("目标版本与当前 embedding 配置不匹配，拒绝回滚（请先确认模型/维度配置后再操作）")
    if health["members"] != health["declared"] or health["physical"] != health["members"]:
        raise ValueError(
            f"目标版本节点不完整（declared={health['declared']} members={health['members']} "
            f"physical={health['physical']}），拒绝回滚")
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(f"UPDATE rag_index_state SET release_id={ph} WHERE name='active'", (release_id,))
