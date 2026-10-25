#!/usr/bin/env bash
# PG不暴露宿主端口；维护进程在Java容器内运行，仍强制关闭自身后台/迁移。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$repo_dir/scripts-java/compose.sh" --apply exec -T backend-java java \
  -Dloader.main=io.dpdk.assistant.operations.OperationsMain -cp /app/backend.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
