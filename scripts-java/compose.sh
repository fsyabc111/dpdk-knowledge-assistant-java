#!/usr/bin/env bash
# J5-03新profile专属入口；所有Docker调用均显式context，禁止ambient/旧.env。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$repo_dir/acceptance/deployment/guard.py" "$repo_dir" >/dev/null
apply=false
if [[ ${1:-} == --apply ]]; then apply=true; shift; fi
compose_base=(docker --context colima-java-u12 compose --env-file "$DPDK_JAVA_ENV_FILE" -f "$repo_dir/docker-compose.java.yml" -p dpdk-java-isolated)
if [[ "$apply" == false ]]; then
  [[ $# == 0 ]] || { echo '默认只展开配置；执行命令需要显式 --apply' >&2; exit 2; }
  exec "${compose_base[@]}" config --quiet
fi
[[ $# -gt 0 ]] || { echo '缺少Compose子命令' >&2; exit 2; }
case "$1" in up|build|ps|logs|stop|exec|run|images|config) ;; *) echo '不支持该子命令（不提供down/卷删除入口）' >&2; exit 2;; esac
# 展开结果只存在进程内存；不把含口令的config写盘或打印。
expanded="$("${compose_base[@]}" config --format json)"
COMPOSE_JAVA_JSON="$expanded" node -e 'const x=JSON.parse(process.env.COMPOSE_JAVA_JSON);if(x.name!=="dpdk-java-isolated" || String(x.services["frontend-java"].user).split(":")[0]==="0" || Object.keys(x.services).includes("harness-java"))process.exit(1)' || { echo '项目/权限/真实harness不符合隔离约束' >&2; exit 1; }
exec "${compose_base[@]}" "$@"
