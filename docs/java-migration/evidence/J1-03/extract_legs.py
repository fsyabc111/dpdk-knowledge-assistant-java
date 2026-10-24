#!/usr/bin/env python3
"""J1-03 SQL 腿提取器：在真实 PG 上按 LiNodeRetrievalStore 的原句 SQL 跑出
四条召回腿 + 行回读顺序，冻结为 legs.json / row-order.json。

只依赖 psql（stdlib 驱动 subprocess）。SQL 语句与
backend/app/storage/li_retrieval_store.py 逐字对应（%s 以字面量代入）。

子命令：
  seed     建库+B0015+灌 fixture（供人工检查，集成测试会自行建动态库）
  legs     读 rewrite-intermediate.json，跑四条腿，写 legs.json
  rows     读 golden 中 loaded_row_ids，跑 fetch_rows_by_ids 原句，写 row-order.json

用法示例见 evidence/J1-03/README.md。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SPEC_DEFAULT = ROOT / "backend-java/src/test/resources/migration/J1-03/fixture-spec.json"
B0015 = ROOT / "backend-java/src/main/resources/db/migration/B0015__baseline.sql"


def psql(database: str, sql: str, ctx) -> str:
    proc = subprocess.run(
        ["psql", "-h", ctx["host"], "-p", ctx["port"], "-U", ctx["user"],
         "-d", database, "-qtA", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=ctx["env"])
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed: {proc.stderr[:2000]}\nSQL(head): {sql[:300]}")
    return proc.stdout


def env_from_args(args) -> dict:
    import os
    env = dict(os.environ)
    env["PGPASSWORD"] = args.password
    return {"host": args.host, "port": str(args.port), "user": args.user,
            "password": args.password, "env": env}


def vector_literal(nonzero, dim=1024) -> str:
    vals = [0.0] * dim
    for idx, val in nonzero or []:
        vals[idx] = float(val)
    return "[" + ",".join(str(x) for x in vals) + "]"


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def seed_sql(spec) -> str:
    table = '"data_' + spec["table_configured"] + '"'
    lines = ["BEGIN;"]
    for node in spec["nodes"]:
        meta = json.dumps(node["metadata"], ensure_ascii=False)
        lines.append(
            f"INSERT INTO {table}(node_id, text, metadata_, embedding) VALUES ("
            f"{quote(node['node_id'])}, {quote(node['text'])}, "
            f"{quote(meta)}::jsonb, {quote(vector_literal(node['nonzero']))}::vector);")
    for rid, rel in spec["releases"].items():
        lines.append(
            f"INSERT INTO rag_releases(id, configuration, created_at, node_count) VALUES ("
            f"{quote(rid)}, {quote(rel['configuration'])}, '2026-10-25T00:00:00+00:00', "
            f"{len(rel['node_ids'])});")
        for nid in rel["node_ids"]:
            lines.append(
                f"INSERT INTO rag_release_nodes(release_id, node_id) VALUES ({quote(rid)}, {quote(nid)});")
    lines.append(f"UPDATE rag_index_state SET release_id={quote(spec['active_release'])} WHERE name='active';")
    for doc in spec["legacy"]["documents"]:
        lines.append(
            "INSERT INTO knowledge_documents(id, source_path, source_type, title, content_hash, git_commit, indexed_at) VALUES ("
            f"{quote(doc['id'])}, {quote(doc['source_path'])}, {quote(doc['source_type'])}, "
            f"{quote(doc['title'])}, {quote(doc['content_hash'])}, "
            + ("NULL, " if doc["git_commit"] is None else quote(doc["git_commit"]) + ", ")
            + f"{quote(doc['indexed_at'])}::timestamptz);")
    for chunk in spec["legacy"]["chunks"]:
        meta = json.dumps(chunk["metadata_json"], ensure_ascii=False)
        lines.append(
            "INSERT INTO knowledge_chunks(id, document_id, source_path, section, content, metadata_json) VALUES ("
            f"{quote(chunk['id'])}, {quote(chunk['document_id'])}, {quote(chunk['source_path'])}, "
            f"{quote(chunk['section'])}, {quote(chunk['content'])}, {quote(meta)}::jsonb);")
    lines.append("COMMIT;")
    return "\n".join(lines)


def cmd_seed(args) -> None:
    ctx = env_from_args(args)
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    psql("postgres", f"DROP DATABASE IF EXISTS {args.database} WITH (FORCE)", ctx)
    psql("postgres", f"CREATE DATABASE {args.database}", ctx)
    psql(args.database, B0015.read_text(encoding="utf-8"), ctx)
    psql(args.database, seed_sql(spec), ctx)
    print(f"seeded {args.database}")


def leg_ids_sql(table: str, release: str, clause: str, args_sql: str, order: str, limit: int) -> str:
    return (f"SELECT c.node_id FROM {table} c "
            f"JOIN rag_release_nodes r ON r.node_id=c.node_id "
            f"WHERE r.release_id={quote(release)} AND ({clause}) "
            f"ORDER BY {order}, c.node_id LIMIT {min(limit, 512)};")


def cmd_legs(args) -> None:
    ctx = env_from_args(args)
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    inter = json.loads(Path(args.intermediate).read_text(encoding="utf-8"))
    table = '"data_' + spec["table_configured"] + '"'
    config = spec["fts_config"]

    def rows(sql: str) -> list:
        out = psql(args.database, sql, ctx)
        return [line for line in out.splitlines() if line.strip()]

    result = {
        "database": args.database,
        "table": table.strip('"'),
        "fts_config": config,
        "metadata_text": {},
        "embedding_text": {},
        "cases": {},
    }
    for node in spec["nodes"]:
        nid = node["node_id"]
        result["metadata_text"][nid] = rows(
            f"SELECT c.metadata_::text FROM {table} c WHERE c.node_id={quote(nid)};")[0]
        result["embedding_text"][nid] = rows(
            f"SELECT c.embedding::text FROM {table} c WHERE c.node_id={quote(nid)};")[0]

    for case in inter["cases"]:
        if not case.get("pinned"):
            continue
        cid = case["case_id"]
        release = case["release"]
        limit = case["limit"]
        candidate_limit = max(limit * 28, 160)
        half = max(candidate_limit // 2, 40)

        fts_r = rows(leg_ids_sql(
            table, release,
            f"c.text_search_tsv @@ websearch_to_tsquery(CAST({quote(config)} AS regconfig), {quote(case['fts_query_rewritten'])})",
            "", f"ts_rank(c.text_search_tsv, websearch_to_tsquery(CAST({quote(config)} AS regconfig), {quote(case['fts_query_rewritten'])})) DESC",
            candidate_limit)) if case["fts_query_rewritten"] else []
        fts_o = rows(leg_ids_sql(
            table, release,
            f"c.text_search_tsv @@ websearch_to_tsquery(CAST({quote(config)} AS regconfig), {quote(case['fts_query_original'])})",
            "", f"ts_rank(c.text_search_tsv, websearch_to_tsquery(CAST({quote(config)} AS regconfig), {quote(case['fts_query_original'])})) DESC",
            half)) if case["fts_query_original"] else []

        fragments = case["path_fragments"]
        if fragments:
            escaped = []
            for f in fragments[:20]:
                e = f.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                escaped.append("'%" + e.replace("'", "''") + "%'")
            clause = " OR ".join("c.metadata_->>'source_path' ILIKE %s" for _ in escaped)
            clause = clause % tuple(escaped)
            path_ids = rows(leg_ids_sql(table, release, clause, "", "c.metadata_->>'source_path'", half))
        else:
            path_ids = []

        vec = case.get("query_vector_literal")
        semantic_ids = rows(leg_ids_sql(
            table, release, "c.embedding IS NOT NULL", "",
            f"c.embedding <=> {quote(vec)}::vector", half)) if vec else []

        result["cases"][cid] = {
            "rewritten": case["rewritten"],
            "embedding_query": case["embedding_query"],
            "fts_query_rewritten": case["fts_query_rewritten"],
            "fts_query_original": case["fts_query_original"],
            "path_fragments": fragments,
            "fts_rewritten_ids": fts_r,
            "fts_original_ids": fts_o,
            "path_ids": path_ids,
            "semantic_ids": semantic_ids,
            "leg_limits": {"fts_rewritten": candidate_limit,
                           "fts_original": half, "path": half, "semantic": half},
        }
        print(f"{cid}: fts_r={fts_r} fts_o={fts_o} path={path_ids} sem={semantic_ids}")

    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {args.out}")


def cmd_rows(args) -> None:
    ctx = env_from_args(args)
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    golden = json.loads(Path(args.golden).read_text(encoding="utf-8"))
    table = '"data_' + spec["table_configured"] + '"'
    orders = {}
    for case in golden["cases"]:
        ids = case.get("loaded_row_ids")
        if not ids:
            continue
        id_list = ",".join(quote(i) for i in ids)
        sql = (f"SELECT c.node_id AS id FROM {table} c "
               f"JOIN rag_release_nodes r ON r.node_id=c.node_id "
               f"WHERE r.release_id={quote(case['release'])} AND c.node_id IN ({id_list});")
        out = psql(args.database, sql, ctx)
        orders[case["case_id"]] = [line for line in out.splitlines() if line.strip()]
    Path(args.out).write_text(json.dumps(orders, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {args.out}: {json.dumps(orders, ensure_ascii=False)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=55481)
    parser.add_argument("--user", default="p1_java")
    parser.add_argument("--password", default="p1-java-synthetic")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("seed")
    p.add_argument("--spec", default=str(SPEC_DEFAULT))
    p.add_argument("--database", required=True)
    p.set_defaults(func=cmd_seed)

    p = sub.add_parser("legs")
    p.add_argument("--spec", default=str(SPEC_DEFAULT))
    p.add_argument("--intermediate", required=True)
    p.add_argument("--database", required=True)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_legs)

    p = sub.add_parser("rows")
    p.add_argument("--spec", default=str(SPEC_DEFAULT))
    p.add_argument("--golden", required=True)
    p.add_argument("--database", required=True)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_rows)

    args = parser.parse_args()
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
