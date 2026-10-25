#!/usr/bin/env bash
# 独立Compose业务备份；始终排除临时源码输入。J5-03再做真实恢复验证。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
apply=false
if [[ ${1:-} == --apply ]]; then apply=true; shift; fi
[[ $# == 1 && "$1" == /* ]] || { echo '用法：backup.sh [--apply] /绝对路径/新备份.dump' >&2; exit 2; }
[[ ! -e "$1" ]] || { echo '备份已存在，拒绝覆盖' >&2; exit 1; }
if [[ "$apply" == false ]]; then echo 'dry-run：将备份Java隔离库，排除public.rag_task_inputs；未连接数据库'; exit 0; fi
umask 077
backup_tmp="$(mktemp "${1}.partial.XXXXXX")"
trap 'rm -f -- "$backup_tmp"' EXIT
"$repo_dir/scripts-java/compose.sh" --apply exec -T postgres-java bash -se > "$backup_tmp" <<'INNER'
set -euo pipefail
identity="$(psql -U dpdk_java_isolated -d dpdk_java_isolated -Atqc "SELECT current_database() || '|' || current_user || '|' || COALESCE(shobj_description(oid,'pg_database'),'') || '|' || current_setting('data_directory') FROM pg_database WHERE datname=current_database()")"
[[ "$identity" == 'dpdk_java_isolated|dpdk_java_isolated|dpdk-java-isolated-rehearsal|/var/lib/postgresql/data/java-isolated' ]] || { echo '备份库身份不匹配' >&2; exit 1; }
exec pg_dump -U dpdk_java_isolated -d dpdk_java_isolated --format=custom --exclude-table-data=public.rag_task_inputs
INNER
[[ -s "$backup_tmp" ]] || { echo '备份为空' >&2; exit 1; }
# 同目录原子发布且避免覆盖已存在文件。
ln "$backup_tmp" "$1"
echo '已完成Java隔离备份；恢复及业务完整性尚需J5-03验证'
