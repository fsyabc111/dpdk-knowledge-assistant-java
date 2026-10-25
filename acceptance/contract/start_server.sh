#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT=${J5_ARTIFACTS:-"$ROOT/.migration-artifacts/j5-01"}
[ -f "$OUT/classpath.env" ] || { echo '先运行 build_server.sh' >&2; exit 2; }
. "$OUT/classpath.env"
cd "$ROOT"
exec "${JAVA_HOME:?需要JDK21}/bin/java" -cp "$CLASSPATH" io.dpdk.assistant.acceptance.contract.CandidateServer
