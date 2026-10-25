#!/bin/bash
# P2 回归公共环境（由各场景脚本 source）。
# ST-03 独立复现约定：
# - 不依赖旧会话临时目录与任何 worktree 绝对路径；临时目录默认在 evidence/logs/tmp
#   （可用 DPDK_REPLAY_TMP 覆盖），身份文件默认 evidence/logs/replay-state.json；
# - PG 参数允许 DPDK_TEST_PG_* 覆盖（保留原默认值），口令只从 DPDK_TEST_PG_PASSWORD
#   读取，脚本内不出现任何口令字面量；
# - JAVA_HOME 不硬编码：优先沿用环境 JAVA_HOME，否则用 PATH 上的 java。
# 注意：不落任何口令/令牌明文到证据；日志脱敏由 mask() 统一处理。

EV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$EV_DIR/logs"
REPLAY_TMP="${DPDK_REPLAY_TMP:-$LOG_DIR/tmp}"
STATE_FILE="${DPDK_REPLAY_STATE:-$LOG_DIR/replay-state.json}"
mkdir -p "$LOG_DIR" "$REPLAY_TMP"
chmod 700 "$REPLAY_TMP" 2>/dev/null || true

# java 定位：不硬编码发行版路径；未设 JAVA_HOME 时信任 PATH
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi

BASE="${DPDK_REPLAY_BASE_URL:-http://127.0.0.1:8090}"
PGHOST="${DPDK_TEST_PG_HOST:-127.0.0.1}"
PGPORT="${DPDK_TEST_PG_PORT:-55481}"
PGUSER="${DPDK_TEST_PG_USER:-p1_java}"
PGDATABASE="${DPDK_TEST_PG_DATABASE:-p1_java_candidate}"
# 合成实例口令从环境读取（不提交字面量、不设默认；实例为仓库专属合成PG，见 .env.example）
export PGPASSWORD="${DPDK_TEST_PG_PASSWORD:?需要 DPDK_TEST_PG_PASSWORD（不设默认口令）}"
export REPLAY_TMP STATE_FILE

psqlq() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1"; }

# —— 身份文件（replay-state.json）读写：本轮动态 token/账号/作业 id 的唯一交换点 ——
# state_get <dotted.key>：打印值，缺失输出空并返回 1
state_get() {
  python3 - "$STATE_FILE" "$1" <<'PYEOF'
import json, sys
path, key = sys.argv[1], sys.argv[2].split(".")
try:
    d = json.load(open(path))
except Exception:
    sys.exit(1)
for k in key:
    if not isinstance(d, dict) or k not in d:
        sys.exit(1)
    d = d[k]
print(d if not isinstance(d, (dict, list)) else json.dumps(d, ensure_ascii=False))
PYEOF
}

# state_set <dotted.key> <value>：写入身份文件（中间表自动创建；文件权限 600）
state_set() {
  python3 - "$STATE_FILE" "$1" "$2" <<'PYEOF'
import json, os, sys
path, key, val = sys.argv[1], sys.argv[2].split("."), sys.argv[3]
try:
    d = json.load(open(path))
except Exception:
    d = {}
cur = d
for k in key[:-1]:
    cur = cur.setdefault(k, {})
    if not isinstance(cur, dict):
        raise SystemExit("state_set: %s 路径冲突" % ".".join(key))
cur[key[-1]] = val
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump(d, f, ensure_ascii=False, indent=1)
os.replace(tmp, path)
os.chmod(path, 0o600)
PYEOF
}

# 运行时生成 bcrypt 哈希（tools/BcryptGen.java；M2_REPO 默认 ~/.m2/repository 通配查找，
# 取版本最高者；输出为哈希列，调用方负责不得把哈希写入提交内容）
bcrypt_hash() {
  local m2="${M2_REPO:-$HOME/.m2/repository}"
  if [ -z "$JAVA_BIN" ]; then
    echo "bcrypt_hash: 未找到 java（设置 JAVA_HOME 或将 java 加入 PATH）" >&2
    return 1
  fi
  local jars cp
  jars=$(python3 - "$m2" <<'PYEOF'
import glob, re, sys
m2 = sys.argv[1]
def best(rel, pat):
    hs = glob.glob("%s/%s/*/%s" % (m2, rel, pat))
    return max(hs, key=lambda p: [int(x) for x in re.findall(r"\d+", p)] or [0]) if hs else ""
rows = [best("org/springframework/security/spring-security-crypto", "spring-security-crypto-*.jar"),
        best("org/springframework/spring-core", "spring-core-*.jar"),
        best("commons-logging/commons-logging", "commons-logging-*.jar")]
print(":".join(r for r in rows if r))
PYEOF
)
  if [ -z "$jars" ]; then
    echo "bcrypt_hash: M2_REPO($m2) 下未找到 spring-security-crypto-*.jar" >&2
    return 1
  fi
  cp="$jars"
  "$JAVA_BIN" -cp "$cp" "$EV_DIR/tools/BcryptGen.java" "$@" | cut -f2
}

# 脱敏：43+ 位 base64url token、bcrypt 哈希、口令字面值
mask() {
  sed -E \
    -e 's/[A-Za-z0-9_-]{43}/<TOKEN_MASKED>/g' \
    -e 's/\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}/<BCRYPT_MASKED>/g' \
    -e 's/"password"[[:space:]]*:[[:space:]]*"[^"]*"/"password":"<PW_MASKED>"/g' \
    -e 's/"new_password"[[:space:]]*:[[:space:]]*"[^"]*"/"new_password":"<PW_MASKED>"/g' \
    -e 's/"old_password"[[:space:]]*:[[:space:]]*"[^"]*"/"old_password":"<PW_MASKED>"/g' \
    -e 's/"reset_token"[[:space:]]*:[[:space:]]*"[^"]*"/"reset_token":"<TOKEN_MASKED>"/g'
}

# 调用并留证：req <名称> <curl 参数...>；原始响应（头+体）写日志并打码
req() {
  local name="$1"; shift
  {
    echo "### $name"
    echo "\$ curl -s -i $*"
    curl -s -i "$@" 2>&1
    echo
    echo
  } | mask >> "$LOG_DIR/${SCENARIO:-scenario}.log"
}

# 单次调用，响应头+体留证，同时把纯 JSON 体存到 $2（供后续解析）：
# reqsave <名称> <体输出文件> <curl 参数...>
reqsave() {
  local name="$1" outfile="$2"; shift 2
  local resp body
  resp=$(curl -s -i "$@" 2>&1)
  {
    echo "### $name"
    echo "\$ curl -s -i $*"
    printf '%s\n' "$resp"
    echo
    echo
  } | mask >> "$LOG_DIR/${SCENARIO:-scenario}.log"
  # 去掉响应头（第 1 行到首个空行），保留体
  body=$(printf '%s' "$resp" | sed '1,/^\r*$/d')
  printf '%s' "$body" > "$outfile"
}

jsonq() { python3 -c "import json,sys;d=json.load(sys.stdin);print(json.dumps(d,ensure_ascii=False,sort_keys=True)[:400])" 2>/dev/null || echo "(non-json body)"; }
