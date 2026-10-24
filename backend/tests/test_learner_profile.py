"""学习者画像聚合（M4a E8）的默认集单测：GradingService.learner_profile。

数据源 grading_submissions（只存 hash + 结果 JSON，D12）；画像含各协议
尝试次数/最高分/高频失败用例名，总预算 300 字。黄金场景 30 覆盖提示词注入接线。
"""
from __future__ import annotations

import pytest

from app.services.grading_service import GradingService


async def _record(grading, user_id, protocol, score, results):
    await grading.record_submission(
        user_id=user_id, session_id=None, protocol=protocol,
        code_hash=f"h_{user_id}_{protocol}_{score}", score=score,
        passed=sum(1 for case in results if case["result"] == "PASS"),
        total=len(results), results=results,
    )


async def test_profile_aggregates_attempts_best_and_frequent_fails(sqlite_db):
    grading = GradingService()
    await _record(grading, "stu_1", "dns", 60, [
        {"name": "dns_basic_1", "result": "PASS"},
        {"name": "dns_basic_3", "result": "FAIL"},
    ])
    await _record(grading, "stu_1", "dns", 80, [
        {"name": "dns_basic_3", "result": "FAIL"},
        {"name": "dns_basic_4", "result": "PASS"},
    ])
    await _record(grading, "stu_1", "http", 40, [
        {"name": "http_get", "result": "FAIL"},
    ])
    profile = await grading.learner_profile("stu_1")
    assert "dns：尝试2次、最高80分" in profile
    assert "dns_basic_3" in profile  # 高频失败用例名
    assert "http：尝试1次、最高40分" in profile
    assert len(profile) <= 300


async def test_profile_empty_for_unknown_user(sqlite_db):
    assert await GradingService().learner_profile("nobody") == ""


async def test_profile_isolated_per_user(sqlite_db):
    grading = GradingService()
    await _record(grading, "stu_2", "ssh", 90, [{"name": "ssh_banner", "result": "PASS"}])
    assert await grading.learner_profile("stu_1") == ""
    profile = await grading.learner_profile("stu_2")
    assert "ssh：尝试1次、最高90分" in profile and "常挂用例" not in profile


async def test_profile_caps_at_300_chars(sqlite_db):
    grading = GradingService()
    for index in range(30):
        await _record(grading, "stu_3", f"proto_{index:02d}", 50, [
            {"name": f"case_with_a_rather_long_name_{index:02d}", "result": "FAIL"},
        ])
    profile = await grading.learner_profile("stu_3")
    assert 0 < len(profile) <= 300


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
