#!/usr/bin/env bash
# Java 主后端的独立维护入口；不读取旧 .env，不执行 Python 后端。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
operations_jar="${DPDK_JAVA_JAR:-$repo_dir/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar}"
[[ -f "$operations_jar" ]] || { echo '缺少 Java jar；先执行 backend-java/./mvnw -DskipTests package' >&2; exit 1; }
exec java -Dloader.main=io.dpdk.assistant.operations.OperationsMain -cp "$operations_jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
