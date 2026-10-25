#!/usr/bin/env bash
# 默认只展开配置；运行前核对显式Docker context/endpoint，绝不切换全局context。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${DPDK_JAVA_ENV_FILE:?必须指定新Java隔离env文件绝对路径}"
[[ "$DPDK_JAVA_ENV_FILE" == /* && -f "$DPDK_JAVA_ENV_FILE" ]] || { echo 'env文件必须存在且为绝对路径' >&2; exit 2; }
apply=false
if [[ ${1:-} == --apply ]]; then apply=true; shift; fi
if [[ "$apply" == false ]]; then
  [[ $# == 0 ]] || { echo '默认只展开配置；执行命令需要显式 --apply' >&2; exit 2; }
  exec docker compose --env-file "$DPDK_JAVA_ENV_FILE" -f "$repo_dir/docker-compose.java.yml" -p dpdk-java-isolated --profile rehearsal config --quiet
fi
: "${DPDK_JAVA_DOCKER_CONTEXT:?必须显式指定本轮独立Java Docker context}"
: "${DPDK_JAVA_DOCKER_ENDPOINT:?必须显式指定该context预期Docker endpoint}"
case "$DPDK_JAVA_DOCKER_CONTEXT" in default|colima|colima-dpdk-ext|dpdk-assistant|'') echo '拒绝默认或旧Docker context' >&2; exit 1;; esac
actual_endpoint="$(docker context inspect "$DPDK_JAVA_DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')"
[[ "$actual_endpoint" == "$DPDK_JAVA_DOCKER_ENDPOINT" ]] || { echo 'Docker endpoint与预期不符，零容器写入退出' >&2; exit 1; }
[[ $# -gt 0 ]] || { echo '缺少Compose子命令' >&2; exit 2; }
case "$1" in up|build|ps|logs|stop|exec|run|images|config) ;; *) echo '不支持该子命令（不提供down/卷删除入口）' >&2; exit 2;; esac
# 在任何容器操作前展开配置并核对代理不能为root。
expanded="$(docker compose --env-file "$DPDK_JAVA_ENV_FILE" -f "$repo_dir/docker-compose.java.yml" -p dpdk-java-isolated config --format json)"
COMPOSE_JAVA_JSON="$expanded" node -e 'const x=JSON.parse(process.env.COMPOSE_JAVA_JSON);if(x.name!=="dpdk-java-isolated" || String(x.services["frontend-java"].user).split(":")[0]==="0")process.exit(1)' || { echo '项目名或代理UID不符合隔离约束' >&2; exit 1; }
profile_args=()
if [[ ${DPDK_JAVA_COMPOSE_PROFILES:-} == rehearsal ]]; then profile_args=(--profile rehearsal);
elif [[ -n ${DPDK_JAVA_COMPOSE_PROFILES:-} ]]; then echo "不支持的profile" >&2; exit 2; fi
exec docker --context "$DPDK_JAVA_DOCKER_CONTEXT" compose --env-file "$DPDK_JAVA_ENV_FILE" -f "$repo_dir/docker-compose.java.yml" -p dpdk-java-isolated "${profile_args[@]}" "$@"
