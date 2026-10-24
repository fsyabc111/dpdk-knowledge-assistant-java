"""Purge sessions idle past the retention cutoff (cron entry point).

Default is a dry-run that only prints what would be deleted:

    python scripts/purge_sessions.py
    python scripts/purge_sessions.py --retention-days 14
    python scripts/purge_sessions.py --apply
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import get_settings
from app.services.session_service import SessionService


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--retention-days", type=int, default=None,
                        help="override SESSION_RETENTION_DAYS (default from settings)")
    parser.add_argument("--apply", action="store_true",
                        help="actually delete; without this flag only counts are printed")
    args = parser.parse_args()

    days = args.retention_days or get_settings().session_retention_days
    result = await SessionService().purge_expired_sessions(days, dry_run=not args.apply)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
