"""产品答案验收工具：候选题集展开、真实接口采样运行、人工评分合并与汇总。

题集 product_acceptance.yml（20 协议 × 3 模板题 + 40 独立题 = 100 题）已声明
human_review_required: true——expected 是候选标签而非教师确认事实，本工具任何
输出（含 auto 自动分类）都不构成人工验收通过。

五种模式（优先级：--evidence > --summarize > --scores 合并 > --run > 默认 list）：

  list（默认，不调模型）：
      python product_acceptance_runner.py
      python product_acceptance_runner.py --expected unanswered --sample 8
      python product_acceptance_runner.py --case protocol_dns_1 --case flow_path

  run（真实调用 /api/chat，消耗模型费用；令牌只从环境变量 RAG_EVAL_TOKEN 读取，
  只进 Authorization 请求头，绝不写入输出文件）：
      RAG_EVAL_TOKEN=... python product_acceptance_runner.py --run --limit 5
      RAG_EVAL_TOKEN=... python product_acceptance_runner.py --run --sample 20 --limit 100

  盲审（离线，不调模型）：逐条引用并排打印「论断句 + 证据正文」，
  证据正文取自应用既有的历史引用取数路径（li_ 节点 / 旧检索存储）：
      python product_acceptance_runner.py --evidence run.json
      python product_acceptance_runner.py --evidence run.json --case protocol_dns_1

  合并人工评分（--out 缺省时覆写 --input）：
      python product_acceptance_runner.py --input run.json --scores scores.yml
      python product_acceptance_runner.py --input run.json --scores scores.json --out merged.json

  汇总一个或多个 run 结果（不带门禁参数时行为与退出码不变）：
      python product_acceptance_runner.py --summarize run1.json run2.json
      python product_acceptance_runner.py --summarize run.json --min-citation-ok-rate 0.9 \
          --max-system-error-rate 0.05 --write-baseline baseline.json
      python product_acceptance_runner.py --summarize run.json --baseline baseline.json

人工评分文件（yaml 或 json，按后缀解析）格式：

  reviewer: 张三
  reviewed_at: "2026-09-13T10:00:00+00:00"
  reviews:
    protocol_dns_1:
      correct: true            # bool 或 null
      claims_supported: true
      citation_ok: false
      privacy_pass: true
      unsupported_citation_indexes: [3]   # 引用级：哪几条引用不支撑其论断
      missing_evidence: false             # 应引而未引
      failure_category: null   # 或 retrieval_miss / insufficient_evidence /
                               # generation_error / false_reject / system_error
      notes: "引用 [3] 与正文不符"

旧形态用法 --run --limit N 保持可用；评分文件写越界引用编号会**报错而不是静默丢弃**。

退出码（汇总模式）：显式给了门禁参数时 fail→1、人工评分覆盖率不足 100% 的
incomplete→2、pass→0；**不给门禁参数时恒为 0**。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
import yaml

SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_ROOT = SCRIPT_DIR.parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from acceptance_lib import (  # noqa: E402
    DISCLAIMER,
    EXPECTED_LABELS,
    GATE_METRICS,
    baseline_payload,
    build_run_record,
    compare_with_baseline,
    evaluate_acceptance,
    evidence_report,
    load_cases,
    merge_review,
    select_cases,
    summarize_runs,
)

DEFAULT_CASES_FILE = SCRIPT_DIR / "product_acceptance.yml"
DEFAULT_OUTPUT_DIR = BACKEND_ROOT / "data" / "product_acceptance"
DEFAULT_BASELINE_PATH = DEFAULT_OUTPUT_DIR / "baseline.json"
# 单题请求超时。**必须可配**：P0.1 实测单题 22–156s，固定 150s 会把最慢的题
# 记成 system_error（假失败）。用 --timeout 或 RAG_EVAL_TIMEOUT_SEC 覆盖。
REQUEST_TIMEOUT_SEC = 150
RUN_PAYLOAD_VERSION = 1
# 结论 → 退出码（只在显式给了门禁参数时才用，见 summarize_mode）。
CONCLUSION_EXIT_CODES = {"pass": 0, "no_gates": 0, "fail": 1, "incomplete": 2}


def _select(args: argparse.Namespace) -> list[dict]:
    """加载题集并按 --case/--expected/--sample 筛选；未知 id/非法 sample 直接退出。"""
    try:
        return select_cases(load_cases(args.cases), ids=args.case, expected=args.expected, sample=args.sample)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc


def _default_output_path() -> Path:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return DEFAULT_OUTPUT_DIR / f"run_{stamp}.json"


def _timeout(args: argparse.Namespace) -> float:
    """单题超时：--timeout > RAG_EVAL_TIMEOUT_SEC > 默认值。"""
    if getattr(args, "timeout", None):
        return float(args.timeout)
    return float(os.environ.get("RAG_EVAL_TIMEOUT_SEC") or REQUEST_TIMEOUT_SEC)


def list_mode(args: argparse.Namespace) -> int:
    """只列题不调模型；保持旧版 JSON 形态（cases / human_review_required / items）。"""
    selected = _select(args)
    print(json.dumps({"cases": len(selected), "human_review_required": True,
                      "items": selected}, ensure_ascii=False, indent=2))
    return 0


async def run_mode(args: argparse.Namespace, transport: httpx.AsyncBaseTransport | None = None) -> int:
    """逐题带 history 顺序 POST /api/chat（非流式），落盘 run payload。

    transport 可注入（测试用 httpx.MockTransport）；默认 None = 真实网络。
    令牌只从环境变量 RAG_EVAL_TOKEN 读取、只进 Authorization 头，绝不写入输出文件。
    """
    selected = _select(args)[: args.limit]
    token = os.environ.get("RAG_EVAL_TOKEN")
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    records: list[dict] = []
    async with httpx.AsyncClient(
        base_url=args.base_url, headers=headers, timeout=_timeout(args), transport=transport
    ) as client:
        for index, case in enumerate(selected, start=1):
            print(f"[{index}/{len(selected)}] running: {case['id']}", flush=True)
            start = time.monotonic()
            payload: dict | None = None
            error = ""
            session_id = None
            try:
                for question in [*(case.get("history") or []), case["question"]]:
                    response = await client.post("/api/chat", json={"message": question, "session_id": session_id})
                    response.raise_for_status()
                    payload = response.json()
                    session_id = payload.get("session_id")
            except Exception as exc:  # HTTP 错误/超时/解析异常：记为 system_error，不中断整轮
                error = f"{type(exc).__name__}: {exc}"
            elapsed_ms = int((time.monotonic() - start) * 1000)
            records.append(build_run_record(case, payload, error, elapsed_ms))
    output = Path(args.output) if args.output else _default_output_path()
    output.parent.mkdir(parents=True, exist_ok=True)
    run_payload = {
        "version": RUN_PAYLOAD_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "disclaimer": DISCLAIMER,
        "human_review_required": True,
        "records": records,
    }
    output.write_text(json.dumps(run_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"run 结果已写入：{output}")
    print(f"共 {len(records)} 题；expected 为候选标签，自动分类仅为初判，需人工评分（--scores 合并）后方为有效结论。")
    return 0


def _load_scores(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in (".yml", ".yaml"):
        return yaml.safe_load(text) or {}
    if path.suffix.lower() == ".json":
        return json.loads(text)
    raise SystemExit(f"人工评分文件后缀须为 .yml/.yaml/.json：{path}")


def merge_mode(args: argparse.Namespace) -> int:
    """合并人工评分进 run 结果；--out 缺省覆写 --input。有错误时退出码非零。"""
    if not args.input:
        raise SystemExit("--scores 需要搭配 --input 指定 run 结果文件")
    run_payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
    scores = _load_scores(Path(args.scores))
    total = len(scores.get("reviews") or {})
    merged, errors = merge_review(run_payload, scores)
    out = Path(args.out) if args.out else Path(args.input)
    out.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"合并完成：{total - len(errors)}/{total} 条评分，已写入 {out}")
    for line in errors:
        print(f"错误：{line}", file=sys.stderr)
    return 1 if errors else 0


def _ensure_app_importable() -> None:
    """把 ``backend/`` 放进 ``sys.path``，好让盲审模式能 import 应用代码。

    Python 只把**脚本所在目录**加进 ``sys.path``，不加 cwd。文档里的调用式是
    ``cd backend && python tests/rag_eval/product_acceptance_runner.py --evidence …``，
    此时 ``sys.path[0]`` 是 ``tests/rag_eval``，``import app`` 直接 ModuleNotFoundError
    ——整篇盲审清单会变成「取证据正文出错」，看上去像证据库有问题。其余模式不需要
    应用代码（``--run`` 走 HTTP），所以只有这里要补。
    """
    root = str(Path(__file__).resolve().parents[2])
    if root not in sys.path:
        sys.path.insert(0, root)


def _lookup_source(chunk_id: str) -> dict | None:
    """按 chunk_id 取证据正文；复用应用既有的历史引用取数路径。

    与 ``app/api/chat.py::_citation_source`` 同一套分派：``li_`` 前缀走
    LlamaIndex 节点，其余走 ``knowledge_chunks`` 历史引用直查。
    不新增取数路径、不加表、不改对外契约。
    """
    if not chunk_id:
        return None
    _ensure_app_importable()
    try:
        if chunk_id.startswith("li_"):
            from app.services.llama_index_service import get_llama_index_source

            return asyncio.run(get_llama_index_source(chunk_id))
        from app.storage.retrieval_store import get_historical_chunk

        return get_historical_chunk(chunk_id)
    except Exception as exc:  # 取数失败不掩盖：盲审清单上标出来，不静默当「无证据」
        return {"content": f"（取证据正文出错：{type(exc).__name__}: {exc}）", "source_path": ""}


def evidence_mode(args: argparse.Namespace) -> int:
    """离线盲审清单：逐条引用并排打印「论断句 + 证据正文」；不调模型。

    这是让 100 题人工盲审真正可执行的那一步——没有证据正文，审稿人只能
    凭印象判断引用是否支撑论断。
    """
    payload = json.loads(Path(args.evidence).read_text(encoding="utf-8"))
    wanted = set(args.case or [])
    records = [
        record for record in (payload.get("records") or [])
        if not wanted or str((record.get("case") or {}).get("id")) in wanted
    ]
    if wanted:
        found = {str((record.get("case") or {}).get("id")) for record in records}
        missing = sorted(wanted - found)
        if missing:
            raise SystemExit(f"run 结果中没有这些 case：{', '.join(missing)}")
    if not records:
        raise SystemExit("run 结果中没有记录可展开")
    sources = {
        str(citation.get("chunk_id")): _lookup_source(str(citation.get("chunk_id") or ""))
        for record in records
        for citation in (record.get("citations") or [])
    }
    for record in records:
        print("\n".join(evidence_report(record, sources)))
        print()
    print(f"共 {len(records)} 题；判定依据是引用到的证据正文，不是模型自述；"
          f"评分请写入 yaml 后用 --scores 合并。")
    return 0


def summarize_mode(args: argparse.Namespace) -> int:
    """读取一个或多个 run payload，打印汇总 JSON；给了门禁时附带判定与退出码。

    退出码：``fail``→1、``incomplete``（人工评分覆盖率不足 100%）→2、
    ``pass``/``no_gates``→0。**不给门禁参数时恒为 0**，不破坏既有调用。
    """
    payloads = [json.loads(Path(name).read_text(encoding="utf-8")) for name in args.summarize]
    summary = summarize_runs(payloads)
    gates = {key: getattr(args, key) for key in GATE_METRICS}
    active = {key: value for key, value in gates.items() if value is not None}
    if not active:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
    verdict = evaluate_acceptance(summary, active)
    report: dict = {"summary": summary, "verdict": verdict}
    if args.write_baseline:
        baseline = baseline_payload(payloads)
        path = Path(args.write_baseline)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report["baseline_written"] = str(path)
        print(f"基线已写入：{path}", file=sys.stderr)
    if args.baseline:
        baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
        report["baseline_diff"] = compare_with_baseline(baseline, baseline_payload(payloads))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    for line in verdict["blocking"]:
        print(f"未通过：{line}", file=sys.stderr)
    return CONCLUSION_EXIT_CODES.get(verdict["conclusion"], 1)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="产品答案验收工具（list / run / 合并 / 汇总四模式，详见模块 docstring）")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES_FILE, help="候选题集 yml 路径")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--run", action="store_true", help="显式开启真实接口调用，会消耗模型费用")
    parser.add_argument("--limit", type=int, choices=range(1, 101), default=1,
                        help="run 模式最多执行的题数（默认 1，配合 --case/--expected/--sample 时需显式调大）")
    parser.add_argument("--case", action="append", default=[], help="只选指定 id 的用例，可多次传")
    parser.add_argument("--expected", choices=list(EXPECTED_LABELS), default=None, help="按候选标签过滤")
    parser.add_argument("--sample", type=int, default=None, help="确定性分层抽样题数（先保标签覆盖再按协议/来源轮转补足）")
    parser.add_argument("--output", default="",
                        help="run 模式输出路径（默认 backend/data/product_acceptance/run_<时间戳>.json，自动建目录）")
    parser.add_argument("--input", default="", help="合并模式：run 结果文件")
    parser.add_argument("--scores", default="", help="合并模式：人工评分 yaml/json 文件")
    parser.add_argument("--out", default="", help="合并模式：输出路径（默认覆写 --input）")
    parser.add_argument("--summarize", nargs="+", default=[], metavar="FILE", help="汇总一个或多个 run 结果文件")
    parser.add_argument("--evidence", default="", metavar="FILE",
                        help="盲审模式：展开 run 结果里每条引用的论断与证据正文（离线，不调模型）")
    parser.add_argument("--timeout", type=float, default=None,
                        help=f"run 模式单题超时秒数（默认 {REQUEST_TIMEOUT_SEC}，或环境变量 RAG_EVAL_TIMEOUT_SEC）")
    # 门禁：默认全部不设线，只有显式传参才参与判定与退出码。
    parser.add_argument("--min-correct-rate", type=float, default=None, help="门禁：可答题答案正确率下限")
    parser.add_argument("--min-citation-ok-rate", type=float, default=None, help="门禁：引用支撑率下限")
    parser.add_argument("--max-false-reject-rate", type=float, default=None, help="门禁：误拒答率上限")
    parser.add_argument("--max-system-error-rate", type=float, default=None, help="门禁：系统错误率上限")
    parser.add_argument("--max-pending-review", type=int, default=None, help="门禁：自动分类未定题数上限")
    parser.add_argument("--max-avg-latency-ms", type=float, default=None, help="门禁：平均耗时上限（毫秒）")
    parser.add_argument("--max-p95-latency-ms", type=float, default=None, help="门禁：P95 耗时上限（毫秒）")
    parser.add_argument("--write-baseline", default="", metavar="FILE",
                        help=f"汇总时把本轮写成基线（默认路径 {DEFAULT_BASELINE_PATH}）")
    parser.add_argument("--baseline", default="", metavar="FILE", help="汇总时与既有基线对比，附回退清单")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.evidence:
        return evidence_mode(args)
    if args.summarize:
        return summarize_mode(args)
    if args.scores:
        return merge_mode(args)
    if args.run:
        return asyncio.run(run_mode(args))
    return list_mode(args)


if __name__ == "__main__":
    raise SystemExit(main())
