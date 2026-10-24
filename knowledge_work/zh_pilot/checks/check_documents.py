#!/usr/bin/env python3
"""只读校验样稿包；--preview-json 将切分预览写到 stdout，不写文件或调用网络。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
from urllib.parse import unquote
import uuid

sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parents[1]
ASSISTANT = BASE.parents[1]
BACKEND = ASSISTANT / 'backend'
REQUIRED = ['适用版本与阅读目标', '阅读顺序与自查问题']
LINK = re.compile(r'\[[^\]\n]*\]\(([^\s)]+)\)')


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def headings(text):
    result = []
    fenced = False
    for line in text.splitlines():
        if line.startswith('```'):
            fenced = not fenced
        elif not fenced and re.match(r'^#{1,6} ', line):
            result.append(line.lstrip('#').strip())
    return result


def check():
    manifest = json.loads((BASE / 'manifest.json').read_text())
    baseline = json.loads((BASE / 'checks/source_baseline.json').read_text())
    repo = Path(manifest['source_repo'])
    commit = manifest['source_commit']
    errors, warnings = [], []
    source_drift = []
    source_files = {}
    for entry in manifest['source_files']:
        snapshot = BASE / entry['snapshot']
        if not snapshot.is_file():
            errors.append(f"缺少快照：{entry['snapshot']}；按 README 恢复")
            continue
        expected = git(repo, 'show', f"{commit}:{entry['path']}")
        actual = snapshot.read_bytes()
        if actual != expected:
            errors.append(f"快照不等于固定提交：{entry['path']}")
        lines = actual.decode('utf-8').splitlines()
        if len(lines) != entry['lines']:
            errors.append(f"快照行数变化：{entry['path']}")
        source_files[entry['path']] = lines
        current = repo / entry['path']
        if not current.is_file() or current.read_bytes() != expected:
            source_drift.append(entry['path'])
    if source_drift:
        warnings.append('当前工作树来源已漂移；样稿仍基于固定快照，需人工评估影响')
    if git(repo, 'rev-parse', 'HEAD').decode().strip() != commit:
        warnings.append('主仓库 HEAD 已变化，不能把样稿称为最新实现')
    # 应用导入仅用于设置读取和纯切分；禁止网络连接及子进程以外的执行功能。
    def blocked(*args, **kwargs):
        raise RuntimeError('本检查禁止网络访问')
    socket.socket.connect = blocked
    socket.socket.connect_ex = blocked
    socket.create_connection = blocked
    sys.path.insert(0, str(BACKEND))
    previous = Path.cwd()
    os.chdir(BACKEND)
    try:
        from app.config import get_settings
        from app.services.ingestion_service import RegexSourceChunker, YamlKnowledgeSource
        source = YamlKnowledgeSource()
        config = source.load_config()
        active_config = str(Path(get_settings().knowledge_config).resolve())
        roots = []
        for item in config.get('sources', []):
            for include in item['include']:
                path = (Path(item['root']).expanduser() / include).resolve()
                roots.append(str(path))
                if BASE.is_relative_to(path) or path.is_relative_to(BASE):
                    errors.append('资料工作区与实际知识源入口重叠')
        selected = {p.resolve() for p in source.iter_files()}
        if any(p.is_relative_to(BASE) for p in selected):
            errors.append('实际候选扫描已包含资料工作区')
        chunker = RegexSourceChunker()
    finally:
        os.chdir(previous)
    previews, known_ids, document_ids = [], set(), set()
    facts_count = 0
    for doc in manifest['documents']:
        uuid.UUID(doc['document_id'])
        if doc['document_id'] in document_ids:
            errors.append('资料 ID 重复')
        document_ids.add(doc['document_id'])
        text = (BASE / doc['document_path']).read_text()
        sections = headings(text)
        for section in REQUIRED:
            if section not in sections:
                errors.append(f"{doc['document_path']} 缺章节：{section}")
        if '```mermaid' not in text or '待人工审核' not in text or commit not in text:
            errors.append(f"{doc['document_path']} 缺图、审核状态或源码版本")
        if re.search(r'\[\d+\]', text):
            errors.append(f"{doc['document_path']} 有易与 RAG 混淆的数字引用")
        evidence = (BASE / doc['evidence_path']).read_text()
        doc_ids = {fact['id'] for fact in doc['facts']}
        mentioned = set(re.findall(r'\b(?:PL|PF)-\d{3}\b', text))
        if mentioned != doc_ids:
            errors.append(f"{doc['document_path']} 事实引用与清单不一致：{sorted(mentioned ^ doc_ids)}")
        for fact in doc['facts']:
            facts_count += 1
            if fact['id'] in known_ids:
                errors.append('事实 ID 重复：' + fact['id'])
            known_ids.add(fact['id'])
            if fact['section'] not in sections or fact['id'] not in evidence:
                errors.append(f"{fact['id']} 正文章节或证据表映射缺失")
            if fact['source_commit'] != commit or not fact['references']:
                errors.append(f"{fact['id']} 版本不一致或无来源")
            for ref in fact['references']:
                lines = source_files.get(ref['path'], [])
                start, end = ref['start_line'], ref['end_line']
                if not 1 <= start <= end <= len(lines):
                    errors.append(f"{fact['id']} 行范围无效：{ref['path']}")
                elif ref['symbol'] not in '\n'.join(lines[start-1:end]):
                    errors.append(f"{fact['id']} 定位文本不在所列范围：{ref['symbol']}")
        chunks = chunker.chunk(text, doc['document_path'], BASE / doc['document_path'])
        parts = []
        for number, chunk in enumerate(chunks, 1):
            size = len(chunk.content)
            if not chunk.content.strip() or size > 2600:
                errors.append(f"{doc['document_path']} 分片 {number} 为空或超过 2600 字符")
            if len(chunk.content.splitlines()) == 1 and chunk.content.startswith('#'):
                errors.append(f"{doc['document_path']} 分片 {number} 只有标题、没有解释")
            if size > 1500:
                warnings.append(f"{doc['document_path']} 分片 {number} 超过当前工具 1500 字符窗口，需人工检查")
            parts.append({'ordinal': number, 'section': chunk.section, 'chars': size, 'content': chunk.content})
        previews.append({'document_path':doc['document_path'], 'chinese_chars':len(re.findall(r'[\u4e00-\u9fff]',text)), 'chunks':parts})
    links = 0
    # 来源快照里的旧文档不是本批可修改内容，不检查其链接。
    for path in BASE.rglob('*.md'):
        if path.is_relative_to(BASE / 'snapshots'):
            continue
        fenced = False
        for line in path.read_text().splitlines():
            if line.startswith('```'):
                fenced = not fenced
                continue
            if fenced:
                continue
            for target in LINK.findall(line):
                if re.match(r'[a-zA-Z][\w+.-]*:', target):
                    continue
                dest, _, fragment = unquote(target).partition('#')
                resolved = (path.parent / dest).resolve() if dest else path
                links += 1
                if not resolved.exists():
                    errors.append(f"{path.relative_to(BASE)} 链接不存在：{target}")
                elif fragment and resolved.suffix == '.md':
                    anchors={re.sub(r'[^\w -]', '', title.lower()).replace(' ', '-') for title in headings(resolved.read_text())}
                    if fragment not in anchors:
                        errors.append(f"{path.relative_to(BASE)} 锚点不存在：{target}")
    # 只做狭窄模式筛查；语义泄露检查由人工承担。
    for p in (BASE/'drafts').glob('*.md'):
        text=p.read_text()
        if re.search(r'\b(?:\d{1,3}\.){3}\d{1,3}\b|sk-[A-Za-z0-9_-]{12,}|BEGIN .*PRIVATE KEY|```(?:c|cpp)\b',text):
            errors.append(f'{p.name} 包含需复核的地址、密钥形态或 C 实现围栏')
    changed = git(ASSISTANT, 'diff', '--name-only', 'HEAD').decode().splitlines()
    outside = [name for name in changed if not name.startswith('knowledge_work/')]
    if outside:
        errors.append('助手已有文件发生改动，须核对归属：' + ', '.join(outside))
    if git(repo,'diff','--name-only','HEAD').strip():
        errors.append('主仓库跟踪文件发生变化，须核对归属')
    return {'status':'passed' if not errors else 'failed','source_commit':commit,
            'source_files':len(source_files),'facts':facts_count,'local_links':links,
            'knowledge_config':active_config,'scan_entry_count':len(roots),'candidate_file_count':len(selected),
            'scan_roots_unchanged':sorted(roots)==sorted(baseline['scan_roots']),
            'source_drift':source_drift,'errors':errors,'warnings':warnings,
            'semantic_review':'静态定位检查不能证明事实正确；语义自审见 reviews/self_review.md，仍待人工审核',
            'documents':previews}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preview-json',action='store_true',help='将完整切分预览输出到 stdout；脚本本身不写文件')
    args=parser.parse_args()
    result=check()
    if args.preview_json:
        print(json.dumps(result,ensure_ascii=False,indent=2))
    else:
        summary={key:value for key,value in result.items() if key!='documents'}
        summary['documents']=[{'path':d['document_path'],'chinese_chars':d['chinese_chars'],'chunks':len(d['chunks']),'max_chunk_chars':max(c['chars'] for c in d['chunks'])} for d in result['documents']]
        print(json.dumps(summary,ensure_ascii=False,indent=2))
    return bool(result['errors'])


if __name__=='__main__':
    raise SystemExit(main())
