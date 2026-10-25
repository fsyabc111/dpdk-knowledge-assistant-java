#!/usr/bin/env python3
"""P2 回归 IP-3：python-golden.json 对真实运行 Java 应用的可重放 route 重放比对。

ST-02 严格判定器（本版重构，替代旧三 tier/STATE-DIFF 口径）：
- HTTP 状态比较是所有分支的硬前置条件：golden 与 live 状态不等即 DIFF，无任何隐式豁免；
- 数组比较保序、保重复：逐元素按序比较；对对象数组额外校验实体双射——
  两侧"去重后原始实体数"必须相等（捕获 [10,20]→[10,10]、同身份重复伪装）；
- 废除隐式 STATE-DIFF 通过：允许的差异必须逐 case 在 DECLARATIONS 声明表显式声明
  （差异类型 + 理由 + 批准依据引用）；未声明的一切差异 = DIFF（计失败）；
- 归一化规则保留（identity/TS/DATE/USER_ID/token→类），仅用于两侧同规则归一化后的
  值级比较；同类型标量的值差不因归一化而消失（score:80→99 = DIFF）。

ST-03 独立复现：
- 所有路径由 __file__ 推导，不依赖任何 worktree 或 /tmp 既存状态；
- 环境配置显式化：BASE URL、PG host/port/user/password/dbname、各角色 token 统一从
  DPDK_REPLAY_* 环境变量或 --state 身份文件（logs/replay-state.json，由 run_00_setup.sh
  初始化、场景脚本/collect_state.py 增量写入；含 token 但绝不含 PG 口令）读取；
  缺失即 fail-fast 并列出全部缺失项；不设默认口令；
- BcryptGen.java 交付于本目录同级 tools/，jar 用 M2_REPO（默认 ~/.m2/repository）通配
  查找，java 从 JAVA_HOME 或 PATH 解析，无绝对用户路径。

两种模式：
- --selftest：不启动应用、不连数据库、不读黄金文件；用合成 golden/live 对证明
  判定器对已知变异全部判 FAIL、对应正例判 PASS（退出码非 0 即自检失败）；
- 默认重放模式：读黄金 → 逐 case 重放/等价流 → 判定 → logs/08-golden-replay.log
  + logs/golden-diff.json（可用 --log/--out 覆盖）。

判定口径（verdict）：
- PASS：状态相等且归一化后值级相等（保序保重复 + 实体双射通过）；
- DIFF：任何未声明差异（计失败）；
- ALLOWED-DIFF：差异类型落在该 case 的显式 ALLOW 声明内（如 DECISIONS.md P2-D1
  的 CORS 403 语义），引用声明依据；
- REPLAYED：命中 EXPECT_STATUS 声明且 live 状态与声明期望一致（如 Python 平台路由
  在 Java 侧 404 的登记）；声明期望不满足 = DIFF；
- SKIP：命中 SKIP 声明（原脚本跳过清单平移，语义不放宽）。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# —— ST-03：路径一律由 __file__ 推导 ——
# scripts/ → P2-regression → evidence → java-migration → docs → 仓库根
SCRIPT_DIR = Path(__file__).resolve().parent
EV_DIR = SCRIPT_DIR.parent                       # evidence/P2-regression
TOOLS_DIR = EV_DIR / "tools"
LOG_DIR = EV_DIR / "logs"
REPO_ROOT = SCRIPT_DIR.parents[4]
GOLDEN_DEFAULT = REPO_ROOT / "migration" / "fixtures" / "p0-v2" / "python-golden.json"
LOG_DEFAULT = LOG_DIR / "08-golden-replay.log"
OUT_DEFAULT = LOG_DIR / "golden-diff.json"
STATE_DEFAULT = LOG_DIR / "replay-state.json"    # 身份文件（.gitignore，含 token 不含 PG 口令）

# —— 归一化规则（承 J2-01 golden-diff.md，保持不变）——
RE_ID = re.compile(r"^(asg_[0-9a-f]{16}|[0-9a-f]{16}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}|<identity:\d+>)$")
RE_TS = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?\+00:00$")
RE_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
RE_TOKEN = re.compile(r"^[A-Za-z0-9_-]{43}$")
RE_USER = re.compile(r"^U\d{11}$")


def norm_scalar(s):
    """仅对命中模式类的字符串归一化为类；其余值原样返回（同类型值差不吞）。"""
    if not isinstance(s, str):
        return s
    if RE_TS.match(s):
        return "<TS>"
    if RE_DATE.match(s):
        return "<DATE>"
    if RE_USER.match(s):
        return "<USER_ID>"
    if RE_ID.match(s) or RE_TOKEN.match(s):
        return "<ID>"
    return s


def type_class(v):
    if v is None:
        return "null"
    if isinstance(v, bool):     # 先于 int：bool 不与 0/1 相等
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "float"
    if isinstance(v, str):
        return "str"
    if isinstance(v, list):
        return "list"
    if isinstance(v, dict):
        return "dict"
    return "other"


def distinct_entity_count(lst):
    """去重后原始实体数（未归一化的原始值序列化去重）。"""
    return len({json.dumps(x, ensure_ascii=False, sort_keys=True) for x in lst})


def norm_eq(g, l, path="$"):
    """保序保重的值级比较；返回差异描述列表（空 = 归一化后相等）。"""
    tg, tl = type_class(g), type_class(l)
    if tg != tl:
        return ["%s 类型不同: golden=%s live=%s" % (path, tg, tl)]
    if isinstance(g, dict):
        gk, lk = set(g.keys()), set(l.keys())
        if gk != lk:
            return ["%s 键集不同: golden独有=%s live独有=%s"
                    % (path, sorted(gk - lk), sorted(lk - gk))]
        diffs = []
        for k in sorted(gk):
            diffs += norm_eq(g[k], l[k], "%s.%s" % (path, k))
        return diffs
    if isinstance(g, list):
        if len(g) != len(l):
            return ["%s 数组长度不同: golden=%d live=%d（保序保重复比较）"
                    % (path, len(g), len(l))]
        diffs = []
        if g and all(isinstance(x, dict) for x in g) and all(isinstance(x, dict) for x in l):
            cg, cl = distinct_entity_count(g), distinct_entity_count(l)
            if cg != cl:
                diffs.append("%s 实体双射校验失败: 去重后原始实体数 golden=%d live=%d（重复身份伪装）"
                             % (path, cg, cl))
        for i, (a, b) in enumerate(zip(g, l)):
            diffs += norm_eq(a, b, "%s[%d]" % (path, i))
        return diffs
    ng, nl = norm_scalar(g), norm_scalar(l)
    if ng != nl:
        return ["%s 值不同: golden=%r live=%r（同类型标量值差不归一化吞掉）" % (path, g, l)]
    return []


# —— 允许差异声明表（ST-02：未声明的一切差异 = DIFF）——
# kind: SKIP（不重放）/ ALLOW（允许的差异数据）/ EXPECT_STATUS（声明 live 期望状态）
@dataclass(frozen=True)
class Decl:
    cid: str = ""            # 精确 case_id 匹配（最高优先）
    suffix: str = ""         # case_id "::" 后缀包含匹配（次优先）
    route: str = ""          # route_id 子串匹配（最低优先）
    kind: str = "ALLOW"
    allow: tuple = ()        # ALLOW：允许的差异数据子集，元素取自 {"STATUS","BODY"}
    expect_status: int = -1  # EXPECT_STATUS：声明的 live 期望状态
    reason: str = ""
    ref: str = ""            # 批准依据引用（决策/登记/留证文件）


PLATFORM_ROUTES = (
    "GET /api/health", "HEAD /api/health", "GET /api/ready", "GET /docs",
    "HEAD /docs", "GET /docs/oauth2-redirect", "HEAD /docs/oauth2-redirect",
    "GET /openapi.json", "HEAD /openapi.json", "GET /redoc", "HEAD /redoc",
    "GET /metrics",
)

STATE_FACE_SKIPS = (
    "POST /api/auth/logout::success",
    "POST /api/auth/password-reset::success",
    "POST /api/admin/users::success",
    "POST /api/admin/users/{user_id}/status::success",
    "POST /api/admin/users/{user_id}/set-password::success",
    "POST /api/admin/users/{user_id}/reset-password::success",
    "POST /api/admin/users/delete::success",
    "POST /api/admin/users/assign::success",
    "POST /api/admin/password-reset-requests/{request_id}/approve::success",
    "POST /api/admin/password-reset-requests/{request_id}/reject::success",
)

ASSIGNMENT_FLOW_SKIPS = (
    "POST /api/assignments::success",
    "PATCH /api/assignments/{assignment_id}::success",
    "POST /api/assignments/{assignment_id}/withdraw::success",
    "POST /api/assignments/{assignment_id}/reactivate::success",
    "GET /api/assignments/{assignment_id}/export::success",
    "GET /api/assignments/{assignment_id}/grades::success",
)

DECLARATIONS = (
    # —— SKIP 清单（原脚本跳过/等价留证理由逐条平移，语义不放宽）——
    Decl(cid="POST /api/chat", kind="SKIP",
         reason="chat 主路由未在当前构建装配（chat 编排属 J3+/J5 范围），不重放",
         ref="P2-regression/README.md 范围外节"),
    Decl(route="/api/chat/stream", kind="SKIP",
         reason="SSE 流式响应，P2 不重放（模型 live 依赖）",
         ref="P2-regression/README.md 范围外节"),
    Decl(route="/api/chat stream", kind="SKIP",
         reason="SSE 流式响应，P2 不重放（模型 live 依赖）",
         ref="P2-regression/README.md 范围外节"),
    Decl(route="/api/rag", kind="SKIP",
         reason="RAG 路由属 J3 未实施", ref="P2-regression/README.md 范围外节"),
    Decl(route="/api/tasks", kind="SKIP",
         reason="任务路由依赖模型 live / 任务恢复，P2 不重放",
         ref="P2-regression/README.md 范围外节"),
    Decl(route="explain", kind="SKIP",
         reason="讲解路由依赖模型 live，P2 不重放",
         ref="P2-regression/README.md 范围外节"),
    Decl(route="/api/internal", kind="SKIP",
         reason="内部维护路由，P2 不重放",
         ref="P2-regression/README.md 范围外节"),
    Decl(cid="POST /api/auth/login::success", kind="SKIP",
         reason="golden 引导账号不在候选库；登录成功等价流已在场景1逐字段留证"
                "（token 43 位/expires_at/must_change_password）",
         ref="logs/01-auth.log"),
    *([Decl(cid=c, kind="SKIP",
            reason="状态面成功流依赖 golden 引导状态；等价流已在场景2逐字段留证"
                   "（部分失败契约/停号/审批一次性令牌）",
            ref="logs/02-admin.log") for c in STATE_FACE_SKIPS]),
    *([Decl(cid=c, kind="SKIP",
            reason="等价流已在场景3/5留证（创建201/items/截止/撤回/CSV BOM+CRLF）",
            ref="logs/03-assignments.log; logs/05-report.log")
       for c in ASSIGNMENT_FLOW_SKIPS]),
    Decl(cid="POST /api/assignments/{assignment_id}/submit::success", kind="SKIP",
         reason="评测成功流已在场景4留证（multipart→fake MCP→成绩；hash-only 落库）",
         ref="logs/04-submission.log"),
    Decl(cid="GET /api/sources/{chunk_id}::success", kind="SKIP",
         reason="知识库种子（p0-history chunk）不在候选库（P2 范围不含 RAG 种子装载）；"
                "unauthorized 案例仍重放",
         ref="golden-diff.md"),
    # —— 平台差异声明（EXPECT_STATUS / ALLOW，依据 DECISIONS.md P2-D1）——
    *([Decl(route=r, kind="EXPECT_STATUS", expect_status=404,
            reason="Python 平台路由（FastAPI/docs/metrics/health 探针）Java 未迁移，"
                   "登记平台差异；Java 探针为 /actuator/health",
            ref="DECISIONS.md P2-D1 平台差异登记")
       for r in PLATFORM_ROUTES]),
    Decl(cid="OPTIONS /api/chat::cors-400", kind="ALLOW", allow=("STATUS", "BODY"),
         reason="非允许来源预检：Python/Starlette 400，Java/Spring CorsFilter 403 同语义拒绝",
         ref="DECISIONS.md P2-D1"),
    Decl(suffix="cors", kind="ALLOW", allow=("STATUS", "BODY"),
         reason="Java 无 POST /api/chat（J3+）；CORS 预检由 Spring 全局 CORS 处理，预检形态登记",
         ref="DECISIONS.md P2-D1; golden-diff.md 平台差异"),
    Decl(route="OPTIONS /api/health", kind="ALLOW", allow=("STATUS", "BODY"),
         reason="无 Origin 普通 OPTIONS：golden 405（FastAPI 语义）；live 为 Java 平台面，登记",
         ref="golden-diff.md 平台差异"),
)


def match_decl(cid, route, suffix):
    """精确 cid > 后缀包含 > route 子串，三段优先级各自顺序扫描，首个命中生效。"""
    suffix = suffix or ""
    for d in DECLARATIONS:
        if d.cid and cid == d.cid:
            return d
    for d in DECLARATIONS:
        if d.suffix and d.suffix in suffix:
            return d
    for d in DECLARATIONS:
        if d.route and d.route in route:
            return d
    return None


def compare_case(cid, route, suffix, gstatus, gbody, lstatus, lbody):
    """严格判定器。返回 (verdict, detail)。HTTP 状态为硬前置条件。"""
    diffs = []
    if gstatus != lstatus:
        diffs.append(("STATUS", "HTTP 状态不同: golden=%s live=%s" % (gstatus, lstatus)))
    body_diffs = norm_eq(gbody, lbody, "$body")
    if body_diffs:
        joined = "; ".join(body_diffs[:8]) + ("…" if len(body_diffs) > 8 else "")
        diffs.append(("BODY", joined))
    if not diffs:
        return "PASS", ("HTTP %s；归一化后值级相等（保序保重复 + 实体双射通过）" % lstatus)
    decl = match_decl(cid, route, suffix)
    kinds = sorted({k for k, _ in diffs})
    if decl is not None and decl.kind == "EXPECT_STATUS":
        if lstatus == decl.expect_status:
            return "REPLAYED", ("声明期望 live HTTP %d 与实测一致：%s（依据 %s；golden=%s）"
                                % (decl.expect_status, decl.reason, decl.ref, gstatus))
        return "DIFF", ("差异 kinds=%s（%s）；声明 EXPECT_STATUS=%d 不满足（实测 %d）——%s（依据 %s）"
                        % (kinds, "; ".join(m for _, m in diffs), decl.expect_status,
                           lstatus, decl.reason, decl.ref))
    if decl is not None and decl.kind == "ALLOW":
        if set(kinds) <= set(decl.allow):
            return "ALLOWED-DIFF", ("差异 kinds=%s 落在显式声明允许范围内：%s（依据 %s）"
                                    % (kinds, decl.reason, decl.ref))
        return "DIFF", ("差异 kinds=%s 超出声明允许 %s：%s（依据 %s）"
                        % (kinds, sorted(decl.allow), decl.reason, decl.ref))
    return "DIFF", ("；".join("%s: %s" % (k, m) for k, m in diffs)
                    + "；无允许差异声明 → 计失败（ST-02：未声明差异即 DIFF）")


# —— 脱敏（与 common.sh mask 同口径，防止 token/口令进入日志）——
MASK_RULES = (
    (re.compile(r"[A-Za-z0-9_-]{43}"), "<TOKEN_MASKED>"),
    (re.compile(r"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}"), "<BCRYPT_MASKED>"),
    (re.compile(r'"(password|new_password|old_password|reset_token)"\s*:\s*"[^"]*"'),
     lambda m: '"%s":"<PW_MASKED>"' % m.group(1)),
)


def mask(text):
    for pat, rep in MASK_RULES:
        text = pat.sub(rep, text)
    return text


# —— ST-03：显式配置（env > --state 身份文件 > fail-fast；口令无默认值）——
REQUIRED_ROLES = ("leader", "teacher_a", "teacher_b", "student_a",
                  "student_b", "free", "viewer", "s7")


@dataclass
class Config:
    base_url: str
    pg_host: str
    pg_port: str
    pg_user: str
    pg_database: str
    pg_password: str
    tokens: dict = field(default_factory=dict)
    asg1: str = ""
    state_path: str = ""


def load_config(args) -> Config:
    state_path = args.state or os.environ.get("DPDK_REPLAY_STATE") or str(STATE_DEFAULT)
    state = {}
    if Path(state_path).exists():
        try:
            state = json.loads(Path(state_path).read_text())
        except Exception as e:
            print("FAIL-FAST: 身份文件 %s 解析失败：%s" % (state_path, e), file=sys.stderr)
            raise SystemExit(2)

    missing = []

    def pick(env_names, state_getter, label):
        for n in env_names:
            v = os.environ.get(n)
            if v:
                return v
        try:
            v = state_getter()
            if v is not None and v != "":
                return str(v)
        except (KeyError, TypeError, IndexError):
            pass
        missing.append(label)
        return None

    base_url = pick(["DPDK_REPLAY_BASE_URL"], lambda: state["base_url"],
                    "BASE URL：DPDK_REPLAY_BASE_URL 或 state.base_url")
    pg_host = pick(["DPDK_REPLAY_PG_HOST", "DPDK_TEST_PG_HOST"], lambda: state["pg"]["host"],
                   "PG host：DPDK_REPLAY_PG_HOST / DPDK_TEST_PG_HOST 或 state.pg.host")
    pg_port = pick(["DPDK_REPLAY_PG_PORT", "DPDK_TEST_PG_PORT"], lambda: state["pg"]["port"],
                   "PG port：DPDK_REPLAY_PG_PORT / DPDK_TEST_PG_PORT 或 state.pg.port")
    pg_user = pick(["DPDK_REPLAY_PG_USER", "DPDK_TEST_PG_USER"], lambda: state["pg"]["user"],
                   "PG user：DPDK_REPLAY_PG_USER / DPDK_TEST_PG_USER 或 state.pg.user")
    pg_database = pick(["DPDK_REPLAY_PG_DATABASE", "DPDK_TEST_PG_DATABASE"],
                       lambda: state["pg"]["database"],
                       "PG dbname：DPDK_REPLAY_PG_DATABASE / DPDK_TEST_PG_DATABASE 或 state.pg.database"
                       "（由 run_00_setup.sh 写入本轮库名）")
    pg_password = (os.environ.get("DPDK_REPLAY_PG_PASSWORD")
                   or os.environ.get("DPDK_TEST_PG_PASSWORD"))
    if not pg_password:
        missing.append("PG 口令：DPDK_REPLAY_PG_PASSWORD / DPDK_TEST_PG_PASSWORD"
                       "（不设默认口令；口令绝不写入 state 文件）")
    tokens = {}
    for role in REQUIRED_ROLES:
        t = (os.environ.get("DPDK_REPLAY_TOKEN_" + role.upper())
             or (state.get("tokens") or {}).get(role))
        if t:
            tokens[role] = t
        else:
            missing.append("token[%s]：DPDK_REPLAY_TOKEN_%s 或 state.tokens.%s"
                           "（由 collect_state.py 登录后写入）" % (role, role.upper(), role))
    asg1 = pick(["DPDK_REPLAY_ASG1"], lambda: state["assignment_ids"]["asg1"],
                "ASG1 作业 id：DPDK_REPLAY_ASG1 或 state.assignment_ids.asg1（run_03 写入）")
    if missing:
        print("FAIL-FAST：缺少 %d 项配置，拒绝重放（不设默认口令/不回退旧 .env）：" % len(missing),
              file=sys.stderr)
        for m in missing:
            print("  - %s" % m, file=sys.stderr)
        print("初始化方式：run_00_setup.sh（建库+身份文件）→ 启动应用 → seed_accounts.sh"
              "（种账号）→ collect_state.py（收 token）→ run_03_assignments.sh（建 ASG1）；"
              "详见 README.md 可复现入口一节。", file=sys.stderr)
        raise SystemExit(2)
    return Config(base_url=base_url, pg_host=pg_host, pg_port=str(pg_port), pg_user=pg_user,
                  pg_database=pg_database, pg_password=pg_password, tokens=tokens,
                  asg1=asg1, state_path=state_path)


# —— 运行时依赖定位（ST-03：无绝对用户路径）——

def resolve_java():
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cand = Path(jh) / "bin" / "java"
        if cand.exists():
            return str(cand)
    java = shutil.which("java")
    if java:
        return java
    raise SystemExit("FAIL-FAST: 未找到 java 可执行文件（设置 JAVA_HOME 或将 java 加入 PATH）")


def _version_key(path):
    nums = re.findall(r"\d+", Path(path).stem)
    return [int(x) for x in nums] or [0]


def find_jar(rel_dir, pattern):
    m2 = Path(os.environ.get("M2_REPO", str(Path.home() / ".m2" / "repository"))).expanduser()
    hits = sorted(glob.glob(str(m2 / rel_dir / "*" / pattern)), key=_version_key)
    return hits[-1] if hits else None


def bcrypt_hash(password):
    """运行时生成 bcrypt（交付 tools/BcryptGen.java，java 单文件源模式）。"""
    java = resolve_java()
    crypto_jar = find_jar("org/springframework/security/spring-security-crypto",
                          "spring-security-crypto-*.jar")
    if not crypto_jar:
        raise SystemExit("FAIL-FAST: M2_REPO 下未找到 spring-security-crypto-*.jar"
                         "（默认 ~/.m2/repository，可用 M2_REPO 覆盖）")
    cp = [crypto_jar]
    cl_jar = find_jar("commons-logging/commons-logging", "commons-logging-*.jar")
    if cl_jar:
        cp.append(cl_jar)
    else:
        print("提示：未找到 commons-logging-*.jar，仅使用 spring-security-crypto 运行",
              file=sys.stderr)
    gen = TOOLS_DIR / "BcryptGen.java"
    proc = subprocess.run([java, "-cp", ":".join(cp), str(gen), password],
                          capture_output=True, text=True)
    if proc.returncode != 0 or not proc.stdout.strip():
        raise SystemExit("FAIL-FAST: BcryptGen 执行失败：%s" % proc.stderr.strip()[:300])
    line = proc.stdout.strip().splitlines()[0]
    return line.split("\t")[1]


def psql(cfg: Config, sql, db=None):
    env = dict(os.environ)
    env["PGPASSWORD"] = cfg.pg_password
    return subprocess.run(
        ["psql", "-h", cfg.pg_host, "-p", cfg.pg_port, "-U", cfg.pg_user,
         "-d", db or cfg.pg_database, "-v", "ON_ERROR_STOP=1", "-tAc", sql],
        env=env, capture_output=True, text=True)


def ensure_session(cfg: Config, sid, user_id, title):
    """幂等重建等价会话+两条消息（重放可重复执行）。"""
    sql = ("INSERT INTO assistant_sessions (id,user_id,title,memory_summary,created_at,updated_at) "
           "VALUES ('%s','%s','%s','',now(),now()) ON CONFLICT (id) DO NOTHING;"
           "INSERT INTO assistant_messages (id,session_id,role,content,metadata_json,created_at) "
           "VALUES ('%s-m1','%s','user','合成提问（回归占位文本）','{}'::jsonb,now()),"
           "('%s-m2','%s','assistant','合成回答（回归占位文本）','{}'::jsonb,now()) "
           "ON CONFLICT (id) DO NOTHING;"
           % (sid, user_id, title, sid, sid, sid, sid))
    r = psql(cfg, sql)
    if r.returncode != 0:
        raise SystemExit("FAIL-FAST: 等价会话重建失败（psql）：%s" % r.stderr.strip()[:300])


# —— 重放模式 ——
def run_replay(args, cfg: Config) -> int:
    golden_path = Path(args.golden or GOLDEN_DEFAULT)
    if not golden_path.exists():
        print("FAIL-FAST: 黄金文件不存在：%s（黄金只读，不允许用 Java 输出反向改写）"
              % golden_path, file=sys.stderr)
        raise SystemExit(2)
    golden = json.loads(golden_path.read_text())
    obs = golden["observations"]
    log_path = Path(args.log or LOG_DEFAULT)
    out_path = Path(args.out or OUT_DEFAULT)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    results = []
    out = open(log_path, "w")
    out.write("# P2 golden 重放（IP-3）逐 case 明细（ST-02 严格判定器）\n")
    out.write("基线 %s；比较口径：HTTP 状态硬前置 + 归一化值级（保序保重复+实体双射）"
              "+ 显式允许差异声明表；未声明差异=DIFF\n" % golden.get("baseline_id"))
    out.write("环境：库 %s@%s:%s + BASE %s；身份文件 %s\n\n"
              % (cfg.pg_database, cfg.pg_host, cfg.pg_port, cfg.base_url, cfg.state_path))

    def record(case_id, verdict, detail):
        detail = mask(detail)
        results.append({"case_id": case_id, "verdict": verdict, "detail": detail})
        out.write("- [%s] %s — %s\n" % (verdict, case_id, detail))
        print("%-13s %s — %s" % (verdict, case_id, detail))

    # 幂等重建等价会话（前轮重放的 DELETE 成功流会消耗一次性会话）
    ensure_session(cfg, "sess-p2reg-01", "U90000000003", "P2回归会话甲")
    ensure_session(cfg, "sess-p2reg-02", "U90000000003", "P2回归会话乙")

    def actor_token(actor):
        return cfg.tokens.get(actor)

    for o in obs:
        cid = o["case_id"]
        route = o["route_id"]
        method, _, tmpl = route.partition(" ")
        suffix = cid.split("::", 1)[1] if "::" in cid else ""
        req = o.get("request") or {}
        actor = req.get("actor") or ""
        inp = req.get("input")
        params = req.get("params")
        pathp = dict(req.get("path_parameters") or {})
        gstatus = o.get("status")
        gbody = o.get("body")

        # —— golden 无状态断言 / SKIP 声明（显式表驱动；未声明不跳过）——
        if gstatus is None:
            record(cid, "SKIP", "golden 无状态断言（重定向/平台面）")
            continue
        skip = match_decl(cid, route, suffix)
        if skip is not None and skip.kind == "SKIP":
            record(cid, "SKIP", "%s（依据 %s）" % (skip.reason, skip.ref))
            continue

        def judge(st, raw):
            lb = body_json(raw) if raw else None
            verdict, detail = compare_case(cid, route, suffix, gstatus, gbody, st, lb)
            record(cid, verdict, detail)
            return verdict

        # —— 等价流与平台探测 ——
        if route in ("OPTIONS /api/chat", "OPTIONS /api/health"):
            if "cors" in suffix:
                st, hd, raw = http(cfg, "OPTIONS", "/api/chat",
                                   headers={"Origin": "http://evil.example",
                                            "Access-Control-Request-Method": "POST"})
            elif route == "OPTIONS /api/chat":
                st, hd, raw = http(cfg, "OPTIONS", "/api/chat",
                                   headers={"Origin": "http://localhost:5173",
                                            "Access-Control-Request-Method": "POST"})
            else:
                st, hd, raw = http(cfg, "OPTIONS", "/api/health")
            judge(st, raw)
            continue
        if route in PLATFORM_ROUTES:
            st, hd, raw = http(cfg, method, tmpl.replace("{assignment_id}", cfg.asg1))
            judge(st, raw)
            continue
        if cid == "POST /api/auth/change-password::success":
            # 等价流：学生05 原密改新密 → 204 空体；新密登录 200；SQL 复位（bcrypt 运行时生成）
            t = cfg.tokens["free"]
            st, hd, raw = http(cfg, "POST", "/api/auth/change-password", token=t,
                               jsontext=json.dumps({"old_password": "U90000000005",
                                                    "new_password": "NewPass-9005"}))
            empty = len(raw) == 0
            st2, _, raw2 = http(cfg, "POST", "/api/auth/login",
                                jsontext=json.dumps({"username": "U90000000005",
                                                     "password": "NewPass-9005"}))
            lb = None if empty else body_json(raw)
            verdict, detail = compare_case(cid, route, suffix, gstatus, None, st, lb)
            if st == gstatus and empty and st2 == 200:
                verdict, detail = "PASS", ("等价流重放：change-password HTTP %d 空体=true；"
                                           "新密码登录 HTTP %d（golden 204/空体）" % (st, st2))
            elif verdict == "PASS":
                verdict, detail = "DIFF", ("HTTP 相等但空体/新密登录断言失败：空体=%s 新密登录=%d"
                                           % (empty, st2))
            h = bcrypt_hash("U90000000005")
            r = psql(cfg, "UPDATE users SET password_hash='%s' WHERE id='U90000000005'" % h)
            if r.returncode != 0:
                raise SystemExit("FAIL-FAST: 密码复位失败（psql）：%s" % r.stderr.strip()[:200])
            record(cid, verdict, detail + "；密码已复位")
            continue
        if cid in ("GET /api/chat/sessions/{session_id}::foreign-tree",
                   "GET /api/assignments/{assignment_id}::foreign-tree",
                   "GET /api/admin/users::viewer-hidden"):
            if "sessions" in cid:
                st, hd, raw = http(cfg, "GET", "/api/chat/sessions/sess-p2reg-01",
                                   token=cfg.tokens["student_b"])
            elif "assignments" in cid:
                st, hd, raw = http(cfg, "GET", "/api/assignments/" + cfg.asg1,
                                   token=cfg.tokens["s7"])
            else:
                st, hd, raw = http(cfg, "GET", "/api/admin/users", token=cfg.tokens["student_a"])
            judge(st, raw)
            continue
        if cid == "PATCH /api/admin/users/{user_id}::success":
            # 等价流：leader 改自建树内 viewer 的 daily_quota（golden: U20270000061 daily_quota 5）
            st, hd, raw = http(cfg, "PATCH", "/api/admin/users/U90000000006",
                               token=cfg.tokens["leader"],
                               jsontext=json.dumps({"daily_quota": 5}))
            judge(st, raw)
            r = psql(cfg, "UPDATE users SET daily_quota=10000 WHERE id='U90000000006'")
            if r.returncode != 0:
                raise SystemExit("FAIL-FAST: 配额复位失败（psql）：%s" % r.stderr.strip()[:200])
            continue
        if cid == "GET /api/assignments/{assignment_id}::success":
            # 等价流：以 admin 创建与 golden 等参的作业（title/desc/deadline/items 全同），
            # 无提交 → 学生视角详情体与 golden 逐字段比较
            st0, _, raw0 = http(cfg, "POST", "/api/assignments", token=cfg.tokens["teacher_a"],
                                jsontext=json.dumps({"title": "合成DNS作业", "description": "P0",
                                                     "deadline": "2099-01-01T00:00:00Z",
                                                     "items": [{"protocol": "dns",
                                                                "points": 20}]}))
            try:
                asg3 = str(json.loads(raw0).get("id") or "")
            except Exception:
                asg3 = ""
            if not asg3:
                record(cid, "DIFF", "等价作业创建失败：HTTP %d body=%s"
                       % (st0, mask(raw0.decode(errors="replace")[:200])))
                continue
            st, hd, raw = http(cfg, "GET", "/api/assignments/" + asg3,
                               token=cfg.tokens["student_a"])
            verdict, detail = compare_case(cid, route, suffix, gstatus, gbody, st,
                                           body_json(raw))
            record(cid, verdict, "等价作业详情（新建无提交作业，学生视角）：" + detail)
            continue
        if cid == "DELETE /api/chat/sessions/{session_id}::success":
            # 专用一次性会话（不消耗其他 case 的等价资源）
            ensure_session(cfg, "sess-p2reg-del", "U90000000003", "P2回归一次性删除")
            st, hd, raw = http(cfg, "DELETE", "/api/chat/sessions/sess-p2reg-del",
                               token=cfg.tokens["student_a"])
            st2, _, raw2 = http(cfg, "GET", "/api/chat/sessions/sess-p2reg-del",
                                token=cfg.tokens["student_a"])
            lb = None if not raw else body_json(raw)
            verdict, detail = compare_case(cid, route, suffix, gstatus, None, st, lb)
            if st == gstatus and not raw and st2 == 404:
                verdict, detail = "PASS", ("等价流删除自有会话：HTTP %d 空体=true；"
                                           "删除后详情 HTTP %d（期望 404）" % (st, st2))
            elif verdict == "PASS":
                verdict, detail = "DIFF", ("HTTP 相等但空体/删除后详情断言失败：空体=%s 详情=%d"
                                           % (len(raw) == 0, st2))
            record(cid, verdict, detail)
            continue
        if route == "POST /api/auth/password-reset-requests" and cid.endswith(
                ("::success", "::success-second", "::success-after-request")):
            # golden 体为空体摘要 {length:0, sha256:e3b0c4…}；live 断言同状态 + 零字节体
            import hashlib
            u = "U90000000003" if "second" not in suffix and "after" not in suffix else "U90000000004"
            st, hd, raw = http(cfg, "POST", "/api/auth/password-reset-requests",
                               jsontext=json.dumps({"username": u}))
            gb = gbody or {}
            sha_ok = (gb.get("length") == 0 and hashlib.sha256(raw).hexdigest() == gb.get("sha256"))
            lb = None if not raw else body_json(raw)
            verdict, detail = compare_case(cid, route, suffix, gstatus, gb, st, lb)
            if verdict == "PASS" and not sha_ok:
                verdict, detail = "DIFF", ("HTTP 相等但空体摘要不符：live 体长=%d golden 期望 length=0"
                                           % len(raw))
            record(cid, verdict, detail + ("；sha256 匹配=%s" % sha_ok))
            continue

        # —— 通用重放 ——
        # 路径参数归一：known id 用本环境等价物，未知 id 保持未知
        def resolve_path(t):
            t = t.replace("{assignment_id}", cfg.asg1 if suffix == "success" else "asg_unknown00000001")
            t = t.replace("{session_id}", "sess-p2reg-01" if suffix == "success" else "sess-unknown000001")
            t = t.replace("{user_id}", pathp.get("user_id", "U90000000003")
                          .replace("U20270000011", "U90000000005")
                          .replace("U20270000001", "U90000000001"))
            t = t.replace("{request_id}", pathp.get("request_id", "req-unknown0000001"))
            t = t.replace("{submission_id}", pathp.get("submission_id", "sub-unknown0000001"))
            t = t.replace("{chunk_id}", pathp.get("chunk_id", "chunk-unknown00001"))
            return t

        path = resolve_path(tmpl)
        token = actor_token(actor)
        jsontext = json.dumps(inp, ensure_ascii=False) if isinstance(inp, dict) else None
        st, hd, raw = http(cfg, method, path, token=token, jsontext=jsontext, params=params)
        lb = body_json(raw) if raw else None

        if suffix == "unauthorized":
            verdict, detail = compare_case(cid, route, suffix, gstatus, gbody, st, lb)
            # 严格断言（golden 另含 CSP 等安全头 = 已登记平台差异，不在此断言；DECISIONS.md P2-D1）
            hdr_ok = (hd.get("WWW-Authenticate") == "Bearer"
                      and (hd.get("Content-Type") or "").startswith("application/json"))
            if verdict == "PASS" and not hdr_ok:
                verdict, detail = "DIFF", ("状态+体相等但 WWW-Authenticate/Content-Type 断言失败："
                                           "WWW-Authenticate=%s Content-Type=%s"
                                           % (hd.get("WWW-Authenticate"), hd.get("Content-Type")))
            record(cid, verdict, detail + "；WWW-Authenticate=%s" % hd.get("WWW-Authenticate"))
        else:
            verdict, detail = compare_case(cid, route, suffix, gstatus, gbody, st, lb)
            record(cid, verdict, detail)

    out.close()
    counts = {}
    for r in results:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    payload = {"summary": counts,
               "comparator": "strict-v2: status-precondition+ordered-dedup+bijection+explicit-decls",
               "cases": results}
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=1))
    print("\nSUMMARY:", counts)
    diff_count = counts.get("DIFF", 0)
    print("DIFF 计失败：%d 项；ALLOWED-DIFF/REPLAYED 均为显式声明结果（见日志依据列）" % diff_count)
    return 1 if diff_count else 0


# —— --selftest：合成数据自检（不启动应用、不连库、不读黄金）——
T43_A = "A" * 43
T43_B = "B" * 43


def selftest() -> int:
    print("== ST-02 判定器自检：合成 golden/live 对，不启动应用、不连库、不读黄金文件 ==")
    checks = []

    def run(name, expect, cid, route, suffix, gstatus, gbody, lstatus, lbody, contains=None):
        verdict, detail = compare_case(cid, route, suffix, gstatus, gbody, lstatus, lbody)
        ok = verdict == expect and (contains is None or contains in detail)
        checks.append((name, expect, verdict, ok))
        print("[%s] %-24s 期望=%s 实测=%s" % ("OK" if ok else "失败", name, expect, verdict))
        print("       detail: %s" % detail[:220])

    # a. golden 200 / live 500 同体 → FAIL（状态硬前置）
    run("a.错状态同体", "DIFF", "SYN::a", "GET /syn", "a", 200, {"score": 80}, 500, {"score": 80},
        contains="HTTP 状态不同")
    # b. 成绩行数组反序 [10,20]→[20,10] → FAIL（保序）
    run("b.数组反序", "DIFF", "SYN::b", "GET /syn", "b", 200,
        {"rows": [{"score": 10}, {"score": 20}]}, 200,
        {"rows": [{"score": 20}, {"score": 10}]}, contains="值不同")
    # c. 标量值 score:80→99 → FAIL（同类型值差不归一化吞掉）
    run("c.标量值差", "DIFF", "SYN::c", "GET /syn", "c", 200, {"score": 80}, 200, {"score": 99},
        contains="值不同")
    # d. 重复身份 [10,20]→[10,10]（归一化后同形）→ FAIL（实体双射捕获）
    run("d.重复行数伪装", "DIFF", "SYN::d", "GET /syn", "d", 200,
        {"rows": [{"score": 10}, {"score": 20}]}, 200,
        {"rows": [{"score": 10}, {"score": 10}]}, contains="实体双射")
    # d2. 身份重复伪装：golden 两个不同实体（同分不同人），live 把同一实体重复两行；
    # 逐元素归一化比较全通过（id→<USER_ID>、分数相等），仅实体双射可捕获
    run("d2.同值重复身份", "DIFF", "SYN::d2", "GET /syn", "d2", 200,
        {"rows": [{"u": "U20270000001", "score": 10}, {"u": "U20270000002", "score": 10}]}, 200,
        {"rows": [{"u": "U20270000009", "score": 10}, {"u": "U20270000009", "score": 10}]},
        contains="实体双射")
    # e. 正例：同构响应（id/时间戳/token/日期不同但归一化后一致）→ PASS
    golden_iso = {"id": "asg_0123456789abcdef",
                  "created_at": "2026-01-01T00:00:00.000001+00:00",
                  "updated_at": "2026-01-01T01:00:00.000002+00:00",
                  "token": T43_A, "title": "合成DNS作业", "deadline": "2099-01-01",
                  "quota": 80,
                  "rows": [{"u": "U20270000001", "d": "2026-01-02", "s": 10},
                           {"u": "U20270000002", "d": "2026-01-03", "s": 20}]}
    live_iso = {"id": "asg_fedcba9876543210",
                "created_at": "2026-06-06T06:06:06.000006+00:00",
                "updated_at": "2026-06-06T07:07:07.000007+00:00",
                "token": T43_B, "title": "合成DNS作业", "deadline": "2099-02-02",
                "quota": 80,
                "rows": [{"u": "U20270000001", "d": "2026-02-02", "s": 10},
                         {"u": "U20270000002", "d": "2026-02-03", "s": 20}]}
    run("e.同构响应正例", "PASS", "SYN::e", "GET /syn", "e", 200, golden_iso, 200, live_iso)
    # e2. 控制正例：重复元素保序保重复相等 → PASS
    run("e2.重复元素保序正例", "PASS", "SYN::e2", "GET /syn", "e2", 200,
        {"rows": [10, 10, 20]}, 200, {"rows": [10, 10, 20]})
    # e3. 负例：同多重集不同序（非对象数组）→ FAIL
    run("e3.非对象数组乱序", "DIFF", "SYN::e3", "GET /syn", "e3", 200,
        {"rows": [10, 10, 20]}, 200, {"rows": [10, 20, 10]}, contains="值不同")
    # e4. 负例：bool/int 类型混淆 → FAIL
    run("e4.bool与int混淆", "DIFF", "SYN::e4", "GET /syn", "e4", 200,
        {"enabled": 1}, 200, {"enabled": True})
    # f. 声明了允许差异的 case 按声明判定（合成声明）
    decl = Decl(cid="SYN::cors-like", kind="ALLOW", allow=("STATUS", "BODY"),
                reason="合成声明：预检拒绝状态 400→403 平台语义", ref="合成自检依据")
    saved = DECLARATIONS
    globals()["DECLARATIONS"] = (decl,)
    run("f.声明内差异", "ALLOWED-DIFF", "SYN::cors-like", "OPTIONS /syn", "cors-like",
        400, {"detail": "Invalid CORS request"}, 403, {"detail": "CORS rejected"})
    run("f2.未声明同差异", "DIFF", "SYN::no-decl", "OPTIONS /syn", "cors-like",
        400, {"detail": "Invalid CORS request"}, 403, {"detail": "CORS rejected"})
    globals()["DECLARATIONS"] = saved
    # f3. 生产声明表：CORS 403（DECISIONS.md P2-D1）
    run("f3.生产表CORS声明", "ALLOWED-DIFF", "OPTIONS /api/chat::cors-400",
        "OPTIONS /api/chat", "cors-400", 400, {"detail": "Invalid CORS request"},
        403, {"detail": "Invalid CORS request"})
    # f4. 生产声明表：Python 平台路由 expect 404
    run("f4.平台路由期望404", "REPLAYED", "GET /api/health::success", "GET /api/health",
        "success", 200, {"status": "ok"}, 404, {"detail": "Not Found"})
    run("f5.平台路由期望不满足", "DIFF", "GET /api/health::success", "GET /api/health",
        "success", 200, {"status": "ok"}, 500, {"detail": "boom"})
    # f6. 生产声明表：SKIP 命中
    d = match_decl("POST /api/assignments/{assignment_id}/submit::success",
                   "POST /api/assignments/{assignment_id}/submit", "success")
    ok_skip = d is not None and d.kind == "SKIP"
    checks.append(("f6.SKIP声明命中", "SKIP", d.kind if d else "None", ok_skip))
    print("[%s] %-24s 期望=%s 实测=%s" % ("OK" if ok_skip else "失败",
          "f6.SKIP声明命中", "SKIP", d.kind if d else "None"))
    # 附加：顶键集不同 / 数组长度不同
    run("x.顶键集不同", "DIFF", "SYN::x", "GET /syn", "x", 200, {"a": 1}, 200, {"b": 1},
        contains="键集不同")
    run("x2.数组长度不同", "DIFF", "SYN::x2", "GET /syn", "x2", 200,
        {"rows": [1, 2]}, 200, {"rows": [1]}, contains="数组长度不同")

    failed = [c for c in checks if not c[3]]
    print("\nSELFTEST SUMMARY: total=%d ok=%d failed=%d"
          % (len(checks), len(checks) - len(failed), len(failed)))
    if failed:
        for name, expect, actual, _ in failed:
            print("  失败项: %s 期望=%s 实测=%s" % (name, expect, actual))
        return 1
    print("全部变异判 FAIL、正例与声明判定符合预期 → 判定器自检通过")
    return 0


# —— HTTP（重放模式专用）——
def http(cfg: Config, method, path, token=None, jsontext=None, params=None, headers=None):
    import urllib.error
    import urllib.request
    from urllib.parse import urlencode
    url = cfg.base_url + path
    if params:
        url += "?" + urlencode(params)
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    data = jsontext.encode() if jsontext is not None else None
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()
    except urllib.error.URLError as e:
        raise SystemExit("FAIL-FAST: 应用不可达 %s%s（%s）；重放需要运行中的应用"
                         % (cfg.base_url, path, e.reason))


def body_json(raw):
    try:
        return json.loads(raw.decode())
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser(description="P2 golden 重放（ST-02 严格判定器 + ST-03 独立复现）")
    ap.add_argument("--selftest", action="store_true",
                    help="合成数据自检：不启动应用、不连库、不读黄金文件")
    ap.add_argument("--state", default=None,
                    help="身份文件路径（默认 logs/replay-state.json；可用 DPDK_REPLAY_STATE）")
    ap.add_argument("--base", default=None, help="覆盖 BASE URL（DPDK_REPLAY_BASE_URL 优先级更高）")
    ap.add_argument("--golden", default=None,
                    help="黄金文件路径（默认 migration/fixtures/p0-v2/python-golden.json）")
    ap.add_argument("--log", default=None, help="逐 case 明细输出（默认 logs/08-golden-replay.log）")
    ap.add_argument("--out", default=None, help="机器可读汇总输出（默认 logs/golden-diff.json）")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())

    cfg = load_config(args)
    if args.base:
        cfg.base_url = args.base
    sys.exit(run_replay(args, cfg))


if __name__ == "__main__":
    main()
