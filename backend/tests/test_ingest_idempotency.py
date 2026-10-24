"""版本化 ingest 幂等性的默认集测试（roadmap C-2）。

真库幂等由 test_rag_versioned_postgres.py（postgres mark）覆盖；本文件用模块级
fake（snapshot_sources/active_release/_existing/_publish + 嵌入 spy）把幂等决策
逻辑搬进默认集：同快照零嵌入调用零发布、内容/元数据变化逐项比较换新 id、配置
指纹漂移强制全量重嵌入、纯删除重发不调用嵌入。
"""
from __future__ import annotations

import pytest

from app.config import get_settings
from app.services import rag_index_service as index


def _desired():
    return [
        {"content": "alpha 内容", "metadata": {"source_key": "repo/a.c", "source_path": "a.c",
                                              "ordinal": 0, "section": "f", "title": "a.c"}},
        {"content": "beta 内容", "metadata": {"source_key": "repo/b.c", "source_path": "b.c",
                                             "ordinal": 0, "section": "g", "title": "b.c"}},
    ]


def _existing_from(desired):
    return [{"id": f"li_{i:032x}", "content": node["content"], "metadata": dict(node["metadata"])}
            for i, node in enumerate(desired)]


class _EmbeddingSpy:
    def __init__(self) -> None:
        self.calls = 0
        self.texts: list[str] = []

    async def embed(self, texts, *, usage=None, stage=""):
        self.calls += 1
        self.texts.extend(texts)
        return [[0.5] * 1024 for _ in texts]


def _fakes(monkeypatch, desired, existing, old_config):
    spy = _EmbeddingSpy()
    published = {}
    monkeypatch.setattr(index, "snapshot_sources", lambda settings: [dict(n, metadata=dict(n["metadata"])) for n in desired])
    monkeypatch.setattr(index, "active_release", lambda: "prev-release")
    monkeypatch.setattr(index, "_existing", lambda table, release: (old_config, existing))
    monkeypatch.setattr(index, "build_embedding_model", lambda settings: spy)

    def _capture_publish(table, expected, release, configuration, desired_nodes, changed):
        published.update(table=table, expected=expected, release=release,
                         desired=desired_nodes, changed=changed)

    monkeypatch.setattr(index, "_publish", _capture_publish)
    get_settings.cache_clear()
    return spy, published


async def test_ingest_same_snapshot_is_fully_idempotent(monkeypatch):
    desired = _desired()
    settings = get_settings()
    spy, published = _fakes(monkeypatch, desired, _existing_from(desired), index.index_configuration(settings))
    result = await index.ingest_release(settings, '"data_li_knowledge_v1"')
    assert result["release_id"] == "prev-release"
    assert result["unchanged"] == 2 and result["inserted_or_updated"] == 0 and result["deleted"] == 0
    assert spy.calls == 0, "同快照不得调用嵌入"
    assert not published, "同快照不得发布新版本"


async def test_ingest_content_change_reembeds_only_changed(monkeypatch):
    desired = _desired()
    existing = _existing_from(desired)
    existing[1]["content"] = "beta 旧内容"
    settings = get_settings()
    spy, published = _fakes(monkeypatch, desired, existing, index.index_configuration(settings))
    result = await index.ingest_release(settings, '"data_li_knowledge_v1"')
    assert result["release_id"] != "prev-release"
    assert result["inserted_or_updated"] == 1 and result["unchanged"] == 1
    assert spy.calls == 1 and spy.texts == ["beta 内容"], "只有变化节点重嵌入"
    changed = published["changed"]
    assert len(changed) == 1 and changed[0]["id"] != existing[1]["id"], "变化节点换新 id"
    kept = {node["id"] for node in published["desired"]}
    assert existing[0]["id"] in kept, "未变节点保留原 id（历史引用不漂移）"


async def test_ingest_config_drift_forces_full_reembed(monkeypatch):
    desired = _desired()
    settings = get_settings()
    spy, published = _fakes(monkeypatch, desired, _existing_from(desired), "旧配置指纹")
    result = await index.ingest_release(settings, '"data_li_knowledge_v1"')
    assert result["inserted_or_updated"] == 2 and result["unchanged"] == 0
    assert spy.calls == 1 and len(spy.texts) == 2, "指纹变化时全量重嵌入"


async def test_ingest_pure_removal_republishes_without_embedding(monkeypatch):
    desired = _desired()
    existing = _existing_from(desired) + [
        {"id": "li_stale", "content": "已下线内容", "metadata": {"source_key": "repo/old.c", "ordinal": 0}}
    ]
    settings = get_settings()
    spy, published = _fakes(monkeypatch, desired, existing, index.index_configuration(settings))
    result = await index.ingest_release(settings, '"data_li_knowledge_v1"')
    assert result["deleted"] == 1 and result["inserted_or_updated"] == 0
    assert spy.calls == 0, "纯删除重发不调用嵌入"
    assert published["release"] != "prev-release"


def test_plan_nodes_reuse_and_change_detection():
    desired = _desired()
    existing = _existing_from(desired)
    planned, changed = index.plan_nodes([dict(n, metadata=dict(n["metadata"])) for n in desired],
                                        existing, same_config=True)
    assert [node["id"] for node in planned] == [row["id"] for row in existing]
    assert changed == []

    changed_metadata = [dict(n, metadata=dict(n["metadata"])) for n in desired]
    changed_metadata[0]["metadata"]["section"] = " renamed"
    planned, changed = index.plan_nodes(changed_metadata, existing, same_config=True)
    assert len(changed) == 1 and planned[0]["id"] != existing[0]["id"], "元数据逐项比较，不等即新 id"

    planned, changed = index.plan_nodes([dict(n, metadata=dict(n["metadata"])) for n in desired],
                                        existing, same_config=False)
    assert len(changed) == 2, "same_config=False 时全部视作变化"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
