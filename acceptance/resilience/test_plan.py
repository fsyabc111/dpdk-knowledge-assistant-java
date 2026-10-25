"""故障控制器的纯负例；不启动子进程或连接PG。"""
import argparse
import asyncio
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from acceptance.resilience.run import CASES, events, until


class PlanTests(unittest.TestCase):
    def test_only_frozen_rto_cases_use_one_second(self):
        self.assertEqual({c["id"] for c in CASES if c.get("rto")}, {"accepted", "before_terminal"})
        self.assertFalse(next(c for c in CASES if c.get("status") == "running").get("rto", False))
        self.assertEqual({c["status"] for c in CASES if c["point"] == "mcp_submitted"},
                         {"finished", "unknown", "query_failed", "running"})

    def test_external_ledger_survives_new_reader_and_counts_calls(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"ledger.jsonl"
            path.write_text('{"tool":"grade_plugin","job_id":"same-job"}\n')
            self.assertEqual(events(path), [{"tool": "grade_plugin", "job_id": "same-job"}])
            self.assertEqual(len(events(path)), 1)

    def test_missing_marker_times_out_instead_of_passing(self):
        with self.assertRaises(RuntimeError):
            asyncio.run(until(lambda: False, seconds=.001, interval=.001))


if __name__ == "__main__":
    unittest.main()
