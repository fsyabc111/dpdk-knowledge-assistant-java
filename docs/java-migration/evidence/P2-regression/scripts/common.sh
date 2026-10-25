#!/bin/bash
# P2 回归公共环境（由各场景脚本 source）。
# 注意：不落任何口令/令牌明文到证据；日志脱敏由 mask() 统一处理。
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

EV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$EV_DIR/logs"
mkdir -p "$LOG_DIR" /tmp/p2reg

BASE=http://127.0.0.1:8090
PGHOST=127.0.0.1; PGPORT=55481; PGUSER=p1_java; PGDATABASE=p1_java_candidate
# 合成实例口令从环境读取（不提交字面量；实例为仓库专属合成PG，见 .env.example）
export PGPASSWORD="${DPDK_TEST_PG_PASSWORD:?需要 DPDK_TEST_PG_PASSWORD}"

psqlq() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1"; }

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
