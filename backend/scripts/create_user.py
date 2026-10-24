"""Create user accounts (M6 bootstrap; M8 起树状层级).

Initial password equals the account id itself; ask users to change it after
first login. Examples:

    python scripts/create_user.py --id U20260000001 --roles leader       # 引导第一个组长（根节点）
    python scripts/create_user.py --file students.csv --roles viewer     # 批量导入（每行一个学号）

树状管理（alembic 0005）：组长（leader）是树根，可在管理面板创建老师（admin）
与学生（viewer）；老师只能创建/管理自己名下的学生。leader 角色只能经本 CLI
授予，API 不开放（防提权）；CLI 创建的账号 created_by 为 NULL（根节点）。
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.services.user_service import UserService


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", action="append", dest="ids", default=[],
                        help="account id (student number, ^U\\d{11}$); repeatable")
    parser.add_argument("--file", default=None,
                        help="text file with one account id per line (merged with --id)")
    parser.add_argument("--roles", default="viewer",
                        help="comma-separated roles, subset of viewer/operator/admin (default: viewer)")
    parser.add_argument("--daily-quota", type=int, default=None,
                        help="per-day chat quota; omit for LLM_DAILY_QUOTA_DEFAULT, 0 for unlimited")
    args = parser.parse_args()

    ids = list(args.ids)
    if args.file:
        ids.extend(
            line.strip()
            for line in Path(args.file).read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    if not ids:
        parser.error("provide at least one --id or a --file")
    roles = [role.strip() for role in args.roles.split(",") if role.strip()]

    result = await UserService().create_users(ids, roles=roles, daily_quota=args.daily_quota)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
