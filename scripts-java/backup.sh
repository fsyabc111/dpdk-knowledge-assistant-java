#!/usr/bin/env bash
# 默认dry-run；实际pg_dump --exclude-table-data=public.rag_task_inputs仅排数据，保留结构。
# Python仅使用独立部署标准库工具，不导入/运行旧Python主后端。
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$repo_dir/acceptance/deployment/backup_cli.py" --repo "$repo_dir" "$@"
