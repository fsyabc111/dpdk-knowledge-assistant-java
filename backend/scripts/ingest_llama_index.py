"""Build the isolated LlamaIndex PostgreSQL index without touching legacy data."""
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.services.llama_index_service import LlamaIndexIngestionService


async def main() -> None:
    print(json.dumps(await LlamaIndexIngestionService().ingest(), ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
