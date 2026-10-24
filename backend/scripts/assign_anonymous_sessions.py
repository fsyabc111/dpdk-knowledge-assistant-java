"""Reassign legacy anonymous sessions to an account (M6 transition).

Default is a dry-run that only prints how many sessions would move:

    python scripts/assign_anonymous_sessions.py --user-id U20260000001
    python scripts/assign_anonymous_sessions.py --user-id U20260000001 --apply
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.services.session_service import SessionService
from app.services.user_service import UserService


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-id", required=True,
                        help="target account id (must already exist in the users table)")
    parser.add_argument("--apply", action="store_true",
                        help="actually reassign; without this flag only the match count is printed")
    args = parser.parse_args()

    if not await UserService().get_user(args.user_id):
        print(json.dumps({"error": f"user not found: {args.user_id}"}, ensure_ascii=False))
        return 1
    result = await SessionService().assign_anonymous_sessions(args.user_id, dry_run=not args.apply)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
