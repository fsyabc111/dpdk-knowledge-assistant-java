import json
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from .core.usage import ModelPrice, parse_prices

ASSISTANT_PROJECT_ROOT = Path(__file__).resolve().parents[2]
BACKEND_ROOT = ASSISTANT_PROJECT_ROOT / "backend"


class Settings(BaseSettings):
    app_env: str = "dev"
    assistant_host: str = "127.0.0.1"
    assistant_port: int = 8090
    database_backend: str = "postgres"
    database_url: str = "postgresql:///assistant"
    database_pool_max_size: int = 32
    # Runtime paths are derived from this module instead of the outer checkout
    # directory name, which may differ in case on a Linux deployment.
    assistant_project_path: Path = ASSISTANT_PROJECT_ROOT
    dpdk_repo_path: Path = ASSISTANT_PROJECT_ROOT.parent
    knowledge_config: Path = BACKEND_ROOT / "data/knowledge_sources.yml"
    rag_provider: str = "llama_index"
    llama_index_table: str = "li_knowledge_v1"
    llama_index_context_window: int = 16384
    llama_index_db_pool_size: int = 12
    # HNSW 近似向量索引（对照实验用，默认关闭 = 精确检索）。开启后 alembic
    # 0009 才会对存量 li 表建索引，ingest 路径也会幂等确保同名索引。
    llama_index_hnsw_enabled: bool = False
    llama_index_hnsw_m: int = 16
    llama_index_hnsw_ef_construction: int = 64
    llama_index_hnsw_ef_search: int = 40  # 40 即 pgvector 默认
    # li 表 text_search_tsv 生成列与 FTS 召回腿共用的 regconfig（roadmap B2）。
    # 默认 simple；zh 需要 deploy/postgres 自定义镜像（zhparser 扩展）且存量表
    # 先跑 alembic 0010 换列，否则 ingest 硬失败、FTS 腿降级并告警。白名单校验
    # 同时是注入防护（该值会被 llama-index 拼进建表 DDL）。
    llama_index_fts_config: str = "simple"
    retrieval_strategy: str = "hybrid_fusion_v2"
    # ---- B3 启发式重排权重（默认值=线上现值；runner 参数扫描用，扫描手册见
    # docs/runbook.md §10「B3 权重参数扫描」）。四组：合并权重 / RRF 先验 /
    # MMR 多样性 / boosts（文件名、主题、标识符、结构体定义）。
    retrieval_w_semantic: float = 0.38
    retrieval_w_lexical: float = 0.24
    retrieval_w_ident: float = 0.18
    retrieval_w_lexical_no_semantic: float = 0.46
    retrieval_w_ident_no_semantic: float = 0.26
    retrieval_rrf_k: int = 60
    retrieval_rrf_prior_scale: float = 14.0
    retrieval_rrf_prior_cap: float = 0.22
    retrieval_mmr_redundancy_penalty: float = 0.18
    retrieval_mmr_per_file_penalty: float = 0.04
    retrieval_mmr_per_file_cap: int = 2
    retrieval_filename_boost_exact: float = 0.50
    retrieval_filename_boost_partial: float = 0.30
    retrieval_topic_boost_specific: float = 0.95
    retrieval_topic_boost_generic: float = 0.85
    retrieval_topic_boost_specific_bare: float = 0.90
    retrieval_topic_boost_generic_bare: float = 0.80
    retrieval_topic_boost_specific_infix: float = 0.60
    retrieval_topic_boost_generic_infix: float = 0.50
    retrieval_ident_header_score: float = 1.0
    retrieval_ident_body_score: float = 0.45
    retrieval_definition_boost_typedef: float = 0.10
    retrieval_definition_boost_funcsig: float = 0.04
    # judge 阶段的**上限**（不是固定值）：实际取 min(本值, 请求剩余预算)，
    # 见 rag_product_service.judge_budget。
    # 20 秒是 P2 之前的拍值，实测不足以覆盖真实上游：同一上游对一次**短**改写调用的
    # 延迟 p50 6.4s / max 46.8s（P2.1 15 题，n=19），judge 要读最多 24,000 字符证据。
    # 60 秒覆盖实测的单次调用上界并留余量，同时仍让 120 秒的请求墙保持为唯一真值。
    rag_validation_timeout_sec: float = 60.0
    rag_request_timeout_sec: float = 120.0
    rag_prepare_timeout_sec: float = 15.0
    rag_max_active_requests: int = 20
    rag_max_waiting_requests: int = 20
    rag_queue_timeout_sec: float = 10.0
    rag_upstream_concurrency: int = 20
    rag_retention_days: int = 180
    rag_context_max_chars: int = 24000
    rag_request_token_budget: int = 48000
    rag_answer_cache_enabled: bool = False
    # N08：在线模型 judge 开关（docs/decisions.md N08）。默认关闭 = 每次回答
    # 只跑确定性检查（empty/invalid_citation/no_evidence/missing_citation），
    # 不打模型；模型核验下沉到 scripts/rag_judge_eval.py 离线验收。
    # 置 true 恢复逐请求在线核验。显式注入的 judge 模型不受本开关约束。
    rag_online_judge_enabled: bool = False
    # ---- S3 任务持久化/恢复护栏（docs/decisions.md §8 冻结）----
    # 单任务后台执行硬时限（冻结值 10 分钟）：超时任务按取消语义终止。
    rag_task_max_seconds: float = 600.0
    # rag_task_inputs 临时执行输入（原始问题 + 寄存源码）的兜底留存小时数；
    # 任务到达终态即清理，本值只是兜底上限。
    rag_task_input_ttl_hours: int = 24
    enable_debug_trace: bool = False
    llm_api_base: str = ""
    llm_api_key: str = ""
    model_name: str = "qwen3.8-flash"
    llm_max_tokens: int = 2048
    llm_max_retries: int = 3
    llm_retry_base_seconds: float = 0.5
    conversation_recent_turns: int = 6
    conversation_summary_max_tokens: int = 800
    conversation_summary_max_chars: int = 4800
    conversation_memory_max_chars: int = 24000
    # Required to access maintenance-only endpoints. It is intentionally not
    # exposed to the browser bundle or normal frontend configuration.
    maintenance_api_key: str = ""
    embedding_api_base: str = ""
    embedding_api_key: str = ""
    embedding_model_name: str = "text-embedding-v4"
    embedding_batch_size: int = 10
    embedding_max_retries: int = 5
    embedding_retry_base_seconds: float = 1.0
    frontend_origins: str = "http://127.0.0.1:5174,http://localhost:5174"
    # Authentication: "local" trusts loopback development traffic with a single
    # admin principal; "token" requires Authorization: Bearer <token> for all
    # chat endpoints, with per-token subject/roles/daily quota in
    # api_tokens_json ({"<token>": {"subject": ..., "roles": [...], "daily_quota": N}});
    # "account" resolves bearer tokens issued by POST /api/auth/login against
    # the api_tokens/users tables, keeping api_tokens_json as an emergency
    # fallback checked first.
    auth_mode: Literal["local", "token", "account"] = "local"
    api_tokens_json: str = "{}"
    # Login tokens (account mode) expire this many days after issue.
    auth_token_ttl_days: int = 7
    # Minimum length enforced by change-password / reset flows.
    password_min_length: int = 8
    # bcrypt cost factor；测试环境降到 4（bcrypt 下限）以免认证用例耗时爆炸。
    bcrypt_rounds: int = 12
    rate_limit_chat_per_minute: int = 20
    llm_daily_quota_default: int = 200
    # ---- M1 agent 层（LangGraph 编排 + harness MCP 评测），仅定义与加载 ----
    # 单次请求的工具调用循环硬上限。
    agent_max_tool_rounds: int = 5
    # agent 编排层 LLM 单次响应的 max_tokens。grade_plugin 调用要在单次响应里
    # 输出完整 plugin_source 工具参数（可达 3-4K+ tokens），旧 RAG 管线的
    # llm_max_tokens=2048 会把参数截断成残码导致编译失败，故独立配置、勿低于 4096。
    agent_llm_max_tokens: int = 8192
    # ---- M4a 运行时护栏（docs/archive/2026-09-18/agent_m4_decisions.md E5/E8/E9/E12，逐项独立开关）----
    # 图内 planner+finalize 累计 token 预算（按 usage_metadata 累加）：超限硬转
    # finalize 兜底；0=关闭。产品受理层另有 rag_request_token_budget（请求级
    # 入口预算），两者独立；此前产品路径复用后者做图内预算，现由本开关接管
    #（默认值相同，无行为变化）。
    agent_request_token_budget: int = 48000
    # 超长上下文修剪：保留最近工具轮原文，更早 ToolMessage 压为摘要
    #（kb_search 保留 top-2 片段），仍超限则安全终止转 fallback。产品路径始终
    # 启用（既有行为）；本开关把它推广到普通 agent 路径，默认开。
    agent_context_trim_enabled: bool = True
    # 学习者画像注入：有评测历史的学生，系统提示词固定注入各协议最高分/尝试
    # 次数/高频失败用例（≤300 字，数据源 grading_submissions）。
    agent_profile_inject_enabled: bool = True
    # teaching_harness 容器的 MCP streamable HTTP 端点（compose 内网地址）。
    harness_mcp_url: str = "http://harness:8100/mcp"
    # 单次评测的后端硬超时、代码大小上限（256 KB）、学生日评测配额
    #（operator 角色豁免，见 docs/archive/2026-09-18/agent_upgrade_plan.md D13）。
    grading_timeout_sec: int = 120
    grading_max_code_bytes: int = 262144
    grading_daily_quota: int = 20
    # 分级反馈：同一协议累计 FAIL 达阈值可升级为定位级建议；
    # allow_explicit_ask 控制"学生明确要求"这一升级条件是否生效。
    feedback_escalation_threshold: int = 3
    feedback_allow_explicit_ask: bool = True
    # M2 参考答案注入：评测出现非 PASS 用例时带外拉取教师参考实现追加进系统
    # 提示词（仅供模型理解错因，红线禁止转述），此为注入源码的字符上限。
    reference_answer_max_chars: int = 20000
    # ---- P1 全链路用量与成本（docs/archive/2026-09-18/next_stage_plan.md §3）----
    # 用量账本总开关。关掉只是不落库，trace 里仍照常聚合（便于对照排查）。
    llm_usage_recording_enabled: bool = True
    # 流式请求是否索取 usage 尾帧（stream_options.include_usage）。上游不支持时
    # 会自动去字段重试一次，但每次都要多花一个 RTT，故单独留开关。
    llm_stream_usage_enabled: bool = True
    # 模型单价表，形如
    # {"qwen3.8-flash":{"input_per_1k":"0.0008","output_per_1k":"0.002","currency":"CNY"}}。
    # 单价写成**字符串**以便 Decimal 精确解析；留空是合法状态（只报用量不出价），
    # 不是配置错误。embedding 模型只有 input_per_1k。
    llm_prices_json: str = ""
    # 价格来源/生效标识（如 "official-2026-09"），随每条记录落库以便审计追溯。
    llm_price_version: str = ""
    # Sessions older than this many days (by updated_at) are eligible for
    # purge_expired_sessions / POST /api/internal/purge-sessions.
    session_retention_days: int = 30
    # Unified JSON logging: stdout always active; LOG_FILE adds a rotating
    # file handler (empty = stdout only, the container-friendly default).
    log_level: str = "INFO"
    log_file: str = ""
    log_max_bytes: int = 50 * 1024 * 1024
    log_backup_count: int = 5

    model_config = SettingsConfigDict(
        env_file=BACKEND_ROOT / ".env", env_file_encoding="utf-8"
    )

    @property
    def db_path(self) -> Path:
        if self.database_backend != "sqlite":
            raise ValueError("db_path is only available when DATABASE_BACKEND=sqlite")
        if not self.database_url.startswith("sqlite:///"):
            raise ValueError("MVP only supports sqlite:/// DATABASE_URL")
        return Path(self.database_url.replace("sqlite:///", "", 1)).resolve()

    @property
    def cors_origins(self) -> List[str]:
        return [item.strip() for item in self.frontend_origins.split(",") if item.strip()]

    @property
    def llm_prices(self) -> Dict[str, ModelPrice]:
        """解析后的单价表；未配置返回空表（只报用量，不出价）。"""
        return parse_prices(self.llm_prices_json)

    @model_validator(mode="after")
    def _validate_auth_and_rate_limit(self) -> "Settings":
        for name in ("rag_validation_timeout_sec", "rag_request_timeout_sec", "rag_prepare_timeout_sec",
                     "rag_max_active_requests", "rag_queue_timeout_sec", "rag_upstream_concurrency",
                     "rag_retention_days", "rag_context_max_chars", "rag_request_token_budget",
                     "rag_task_max_seconds"):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name.upper()} must be > 0")
        if self.rag_task_input_ttl_hours < 1:
            raise ValueError("RAG_TASK_INPUT_TTL_HOURS must be >= 1")
        if self.rag_max_waiting_requests < 0:
            raise ValueError("RAG_MAX_WAITING_REQUESTS must be >= 0")
        if self.llama_index_fts_config not in {"simple", "zh"}:
            raise ValueError("LLAMA_INDEX_FTS_CONFIG 仅支持 simple 或 zh（zh 需 deploy/postgres 自定义镜像 + alembic 0010）")
        for name in ("retrieval_w_semantic", "retrieval_w_lexical", "retrieval_w_ident",
                     "retrieval_w_lexical_no_semantic", "retrieval_w_ident_no_semantic",
                     "retrieval_rrf_prior_scale", "retrieval_rrf_prior_cap",
                     "retrieval_mmr_redundancy_penalty", "retrieval_mmr_per_file_penalty",
                     "retrieval_filename_boost_exact", "retrieval_filename_boost_partial",
                     "retrieval_topic_boost_specific", "retrieval_topic_boost_generic",
                     "retrieval_topic_boost_specific_bare", "retrieval_topic_boost_generic_bare",
                     "retrieval_topic_boost_specific_infix", "retrieval_topic_boost_generic_infix",
                     "retrieval_ident_header_score", "retrieval_ident_body_score",
                     "retrieval_definition_boost_typedef", "retrieval_definition_boost_funcsig"):
            if getattr(self, name) < 0:
                raise ValueError(f"{name.upper()} must be >= 0")
        if self.retrieval_rrf_k < 1:
            raise ValueError("RETRIEVAL_RRF_K must be >= 1")
        if self.retrieval_mmr_per_file_cap < 1:
            raise ValueError("RETRIEVAL_MMR_PER_FILE_CAP must be >= 1")
        if self.rate_limit_chat_per_minute < 1:
            raise ValueError("RATE_LIMIT_CHAT_PER_MINUTE must be >= 1")
        if self.agent_max_tool_rounds < 1:
            raise ValueError("AGENT_MAX_TOOL_ROUNDS must be >= 1")
        if self.agent_request_token_budget < 0:
            raise ValueError("AGENT_REQUEST_TOKEN_BUDGET must be >= 0（0=关闭）")
        if self.agent_llm_max_tokens < 2048:
            raise ValueError("AGENT_LLM_MAX_TOKENS must be >= 2048")
        if self.grading_timeout_sec < 1:
            raise ValueError("GRADING_TIMEOUT_SEC must be >= 1")
        if self.grading_max_code_bytes < 1:
            raise ValueError("GRADING_MAX_CODE_BYTES must be >= 1")
        if self.grading_daily_quota < 0:
            raise ValueError("GRADING_DAILY_QUOTA must be >= 0")
        if self.feedback_escalation_threshold < 1:
            raise ValueError("FEEDBACK_ESCALATION_THRESHOLD must be >= 1")
        if self.reference_answer_max_chars < 1000:
            raise ValueError("REFERENCE_ANSWER_MAX_CHARS must be >= 1000")
        if self.auth_token_ttl_days < 1:
            raise ValueError("AUTH_TOKEN_TTL_DAYS must be >= 1")
        if self.password_min_length < 1:
            raise ValueError("PASSWORD_MIN_LENGTH must be >= 1")
        if not 4 <= self.bcrypt_rounds <= 31:
            raise ValueError("BCRYPT_ROUNDS must be between 4 and 31")
        if self.llm_prices_json.strip():
            try:
                raw_prices = json.loads(self.llm_prices_json)
            except json.JSONDecodeError as exc:
                raise ValueError(f"LLM_PRICES_JSON is not valid JSON: {exc}") from exc
            if not isinstance(raw_prices, dict):
                raise ValueError("LLM_PRICES_JSON must be a JSON object keyed by model name")
            parsed_prices = self.llm_prices
            for model, spec in raw_prices.items():
                if not isinstance(model, str) or not model.strip():
                    raise ValueError("LLM_PRICES_JSON keys must be non-empty model names")
                if not isinstance(spec, dict):
                    raise ValueError(f"price spec for {model} must be a JSON object")
                # 启动期从严：写错的价格要在启动时立刻报错，而不是被静默跳过、
                # 上线后才发现一列 cost_micros 全是 NULL。运行期解析器仍然宽容。
                if model not in parsed_prices:
                    raise ValueError(
                        f"price spec for {model} needs a non-negative decimal string "
                        "'input_per_1k' (optional 'output_per_1k') and a non-empty 'currency'"
                    )
        try:
            raw_tokens = json.loads(self.api_tokens_json or "{}")
        except json.JSONDecodeError as exc:
            raise ValueError(f"API_TOKENS_JSON is not valid JSON: {exc}") from exc
        if not isinstance(raw_tokens, dict):
            raise ValueError("API_TOKENS_JSON must be a JSON object keyed by token")
        allowed_roles = {"viewer", "operator", "admin", "leader"}
        for token, spec in raw_tokens.items():
            if not isinstance(token, str) or len(token) < 32:
                raise ValueError("API_TOKENS_JSON keys (tokens) must be strings of at least 32 characters")
            if not isinstance(spec, dict) or not isinstance(spec.get("subject"), str):
                raise ValueError(f"token spec for subject must be a dict with a string 'subject' (token ending ...{token[-4:]})")
            roles = spec.get("roles")
            if not isinstance(roles, list) or not roles or not set(roles) <= allowed_roles:
                raise ValueError(
                    f"token roles must be a non-empty list subset of {sorted(allowed_roles)} "
                    f"(token ending ...{token[-4:]})"
                )
            daily_quota = spec.get("daily_quota", self.llm_daily_quota_default)
            if not isinstance(daily_quota, int) or daily_quota < 0:
                raise ValueError(
                    f"token daily_quota must be a non-negative integer (token ending ...{token[-4:]})"
                )
        if self.auth_mode == "token" and not raw_tokens:
            raise ValueError("AUTH_MODE=token requires at least one entry in API_TOKENS_JSON")
        # account 模式下 API_TOKENS_JSON 仅作应急兜底，允许为空。
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
