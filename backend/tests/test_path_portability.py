"""Regression checks for Linux case-sensitive project paths."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import ASSISTANT_PROJECT_ROOT, Settings, get_settings
from app.services.knowledge_sources import YamlKnowledgeSource


@pytest.fixture
def defaults():
    """Settings built without loading a developer's .env, for default-path validation."""
    return Settings(_env_file=None)


@pytest.fixture
def knowledge_source():
    """The ``knowledge_base`` entry from the YAML ingestion config."""
    config = YamlKnowledgeSource().load_config()
    return next(item for item in config["sources"] if item["name"] == "knowledge_base")


def test_default_assistant_project_path(defaults):
    assert defaults.assistant_project_path == ASSISTANT_PROJECT_ROOT


def test_default_dpdk_repo_path(defaults):
    assert defaults.dpdk_repo_path == ASSISTANT_PROJECT_ROOT.parent


def test_knowledge_source_root_matches_project_path(knowledge_source):
    settings = get_settings()
    assert Path(knowledge_source["root"]).resolve() == settings.assistant_project_path.resolve()


def test_knowledge_source_include(knowledge_source):
    assert knowledge_source["include"] == ["knowledge/"]


def test_yaml_has_no_windows_style_uppercase_path():
    raw_yaml = get_settings().knowledge_config.read_text(encoding="utf-8")
    assert "AI_CHAT_ASSISTANT/knowledge" not in raw_yaml


def test_yaml_has_no_windows_style_lowercase_path():
    raw_yaml = get_settings().knowledge_config.read_text(encoding="utf-8")
    assert "ai_chat_assistant/knowledge" not in raw_yaml


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
