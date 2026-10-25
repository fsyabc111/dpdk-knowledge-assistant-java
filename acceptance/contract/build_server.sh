#!/bin/sh
# 仅编译，绝不连接PG；依赖和产物留gitignored目录。
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT=${J5_ARTIFACTS:-"$ROOT/.migration-artifacts/j5-01"}
mkdir -p "$OUT"
cd "$ROOT"
./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$OUT/classpath.txt"
printf '%s\n' "CLASSPATH=$ROOT/backend-java/target/test-classes:$ROOT/backend-java/target/classes:$(cat "$OUT/classpath.txt")" > "$OUT/classpath.env"
printf '%s\n' "构建完成；用 acceptance/contract/start_server.sh 显式环境启动"
