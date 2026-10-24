#!/usr/bin/env python3
"""只读校验 zh_batch03 资料包（一篇 TLS 专题，前缀 TLS）。

设计沿用首批 `zh_pilot/checks/check_documents.py`，但本批文件清单、事实前缀
（SL/DNS）、仓库状态与通过数字全部独立取得，不复用首批常量。
默认只读：结果写 stdout，`--preview-json` 输出完整切分内容；不写文件、不建库、
不 ingest、不调用模型，并阻断 socket 建连。
"""
from __future__ import annotations

import argparse
import hashlib
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
PILOT = BASE.parent / 'zh_pilot'
BATCH02 = BASE.parent / 'zh_batch02'
OWN = re.compile(r'\bTLS-\d{3}\b')
FOREIGN = re.compile(r'\b(?:PL|PF|SL|SF|DN|DR)-\d{3}\b')
LINK = re.compile(r'\[[^\]\n]*\]\(([^\s)]+)\)')
REQUIRED = ['适用版本与阅读目标', '核心对象和术语', '边界、异常与常见误解', '阅读顺序与自查问题']
SUGGESTED = (1400, 1900)   # 任务书为单篇 22 项事实设定的建议区间
DEFENSIBLE = (1000, 2250)  # 超出即按堆砌处理
CHUNK_HARD_LIMIT = 2600      # 与 RegexSourceChunker.split_long_chunk 的 max_chars 一致
VISIBLE_WINDOW = 1500        # 当前工具可见窗口，超出仅告警


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def headings(text):
    result, fenced = [], False
    for line in text.splitlines():
        if line.startswith('```'):
            fenced = not fenced
        elif not fenced and re.match(r'^#{1,6} ', line):
            result.append(line.lstrip('#').strip())
    return result


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check():
    manifest = json.loads((BASE / 'manifest.json').read_text())
    baseline = json.loads((BASE / 'checks' / 'source_baseline.json').read_text())
    repo = Path(manifest['source_repo'])
    commit = manifest['source_commit']
    errors, warnings, source_drift = [], [], []
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
        errors.append('本批来源的工作树副本与固定提交不同，不得用作依据：' + ', '.join(source_drift))
    if git(repo, 'rev-parse', 'HEAD').decode().strip() != commit:
        warnings.append('主仓库 HEAD 已不等于本批固定提交；资料仍绑定固定快照，不得称为最新实现')

    # 前两批资料前向保护：基线只保护重采之后的改动。
    for key, root in (('pilot_snapshot_hashes', PILOT), ('batch02_snapshot_hashes', BATCH02)):
        for rel, digest in baseline.get(key, {}).items():
            path = root / rel
            if not path.is_file():
                errors.append(f'前批文件缺失：{root.name}/{rel}')
            elif sha(path) != digest:
                errors.append(f'前批文件内容已变化，须核对归属：{root.name}/{rel}')

    # 只导入设置、扫描器与纯切分器；禁止网络。
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
                    errors.append('本批工作区与实际知识源入口重叠')
        selected = {p.resolve() for p in source.iter_files()}
        if any(p.is_relative_to(BASE) for p in selected):
            errors.append('实际候选扫描已包含本批工作区')
        chunker = RegexSourceChunker()
    finally:
        os.chdir(previous)

    own_ids, document_ids, facts_count = set(), set(), 0
    foreign_known = set()
    batch_ids = {f['id'] for d in manifest['documents'] for f in d['facts']}
    for prior in (PILOT, BATCH02):
        try:
            data = json.loads((prior / 'manifest.json').read_text())
        except FileNotFoundError:
            warnings.append(f'未找到前批清单 {prior.name}，跨批引用只能做格式检查'); continue
        foreign_known |= {f['id'] for d in data['documents'] for f in d['facts']}
    previews = []
    for doc in manifest['documents']:
        try:
            uuid.UUID(doc['document_id'])
        except ValueError:
            errors.append(f"{doc['document_path']} 资料 ID 不是 UUID")
        if doc['document_id'] in document_ids:
            errors.append('资料 ID 重复')
        document_ids.add(doc['document_id'])

        text = (BASE / doc['document_path']).read_text()
        sections = headings(text)
        for section in REQUIRED:
            if section not in sections:
                errors.append(f"{doc['document_path']} 缺章节：{section}")
        for label, ok in (('流程图', '```mermaid' in text), ('审核状态', '待人工审核' in text),
                          ('源码版本', commit in text), ('未执行说明', '未' in text)):
            if not ok:
                errors.append(f"{doc['document_path']} 缺{label}")
        if re.search(r'(?<![\w.\)\]])\[\d+\]', text):   # 排除 payload[0] 这类数组下标
            errors.append(f"{doc['document_path']} 有易与 RAG 引用混淆的数字方括号")
        if '```c\n' in text or '```cpp' in text:
            errors.append(f"{doc['document_path']} 含 C/C++ 实现围栏")

        evidence = (BASE / doc['evidence_path']).read_text()
        doc_ids = {f['fact_id'] if 'fact_id' in f else f['id'] for f in doc['facts']}
        own_ids |= doc_ids
        mentioned = set(OWN.findall(text))
        missing = doc_ids - mentioned
        stray = mentioned - doc_ids - batch_ids
        if missing:
            errors.append(f"{doc['document_path']} 事实在正文中未被引用：{sorted(missing)}")
        if stray:
            errors.append(f"{doc['document_path']} 引用了本批未登记的事实：{sorted(stray)}")
        cross_doc = sorted((mentioned & batch_ids) - doc_ids)
        length = len(re.findall(r'[一-鿿]', text))
        # 建议区间 1200-2000；本批每篇承载 19-23 条独立核实的事实，超出建议区间时
        # 只告警并须在 delivery 中说明；超过 2250 视为堆砌，按失败处理。
        if not DEFENSIBLE[0] <= length <= DEFENSIBLE[1]:
            errors.append(f"{doc['document_path']} 中文字数 {length} 超出可辩护区间 "
                          f"{DEFENSIBLE[0]}-{DEFENSIBLE[1]}")
        elif not SUGGESTED[0] <= length <= SUGGESTED[1]:
            warnings.append(f"{doc['document_path']} 中文字数 {length} 超出任务书建议区间 "
                            f"{SUGGESTED[0]}-{SUGGESTED[1]}，须在交付说明中登记原因与是否拆分")

        for fact in doc['facts']:
            facts_count += 1
            if fact['section'] not in sections:
                errors.append(f"{fact['id']} 的正文位置不是实际章节：{fact['section']}")
            if fact['id'] not in evidence:
                errors.append(f"{fact['id']} 未出现在证据表")
            if fact['source_commit'] != commit or not fact['references']:
                errors.append(f"{fact['id']} 版本不一致或无来源")
            for ref in fact['references']:
                lines = source_files.get(ref['path'], [])
                start, end = ref['start_line'], ref['end_line']
                if not 1 <= start <= end <= len(lines):
                    errors.append(f"{fact['id']} 行范围无效：{ref['path']}")
                elif ref['symbol'] not in '\n'.join(lines[start - 1:end]):
                    errors.append(f"{fact['id']} 定位文本不在所列范围：{ref['symbol']}")

        for target in FOREIGN.findall(text):
            if target not in foreign_known:
                errors.append(f"{doc['document_path']} 跨批引用不存在：{target}")

        parts = []
        for number, chunk in enumerate(chunker.chunk(text, doc['document_path'], BASE / doc['document_path']), 1):
            body, size = chunk.content, len(chunk.content)
            if not body.strip():
                errors.append(f"{doc['document_path']} 分片 {number} 为空")
            if size > CHUNK_HARD_LIMIT:
                errors.append(f"{doc['document_path']} 分片 {number} 超过切分上限 {CHUNK_HARD_LIMIT}")
            if len(body.splitlines()) == 1 and body.startswith('#'):
                errors.append(f"{doc['document_path']} 分片 {number} 只有标题、没有解释")
            if body.count('```') % 2:
                errors.append(f"{doc['document_path']} 分片 {number} 代码围栏被切开")
            if size > VISIBLE_WINDOW:
                warnings.append(f"{doc['document_path']} 分片 {number} 超过 {VISIBLE_WINDOW} 字符可见窗口，需重点复读")
            for marker in ('来源：', '来源:', '事实 '):
                if marker in body and not OWN.search(body):
                    errors.append(f"{doc['document_path']} 分片 {number} 声明来源却没有事实编号")
            parts.append({'ordinal': number, 'section': chunk.section, 'chars': size, 'content': body})
        previews.append({'document_path': doc['document_path'],
                         'chinese_chars': length,
                         'cross_document_fact_refs': cross_doc,
                         'chunks': parts})

    links = 0
    for path in sorted(BASE.rglob('*.md')):
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
                    anchors = {re.sub(r'[^\w -]', '', t.lower()).replace(' ', '-') for t in headings(resolved.read_text())}
                    if fragment not in anchors:
                        errors.append(f"{path.relative_to(BASE)} 锚点不存在：{target}")

    # 本机绝对路径一旦写进正文或证据表，资料包换机即失效；来源仓库位置只允许出现在 manifest/baseline。
    for path in sorted(BASE.rglob('*.md')):
        if path.is_relative_to(BASE / 'snapshots'):
            continue
        for hit in re.findall(r'(?:/Users/|/home/|[A-Za-z]:\\\\)\S*', path.read_text()):
            errors.append(f"{path.relative_to(BASE)} 含本机绝对路径：{hit[:40]}")

    for path in sorted(list((BASE / 'drafts').glob('*.md')) + list((BASE / 'evidence').glob('*.md'))):
        text = path.read_text()
        if re.search(r'\b(?:\d{1,3}\.){3}\d{1,3}\b|sk-[A-Za-z0-9_-]{12,}|BEGIN .*PRIVATE KEY|password\s*=|token\s*=', text):
            errors.append(f'{path.relative_to(BASE)} 含需复核的地址、凭据形态或密钥文本')
        if re.search(r'(?:学生|学号)\s*\d{6,}', text):
            errors.append(f'{path.relative_to(BASE)} 含疑似学生标识')

    status = git(ASSISTANT, 'status', '--porcelain').decode().splitlines()
    outside = [line for line in status if not line[3:].startswith('knowledge_work/')]
    if outside:
        errors.append('助手仓库 knowledge_work/ 之外存在改动，须核对归属：' + '; '.join(outside))
    if git(repo, 'status', '--porcelain').decode().strip() not in ('?? ai_chat_assistant/', ''):
        errors.append('主仓库工作区状态与本批登记的基线不同，须核对归属')

    return {'status': 'passed' if not errors else 'failed', 'batch': manifest['batch'],
            'source_commit': commit, 'assistant_commit': manifest['assistant_commit'],
            'source_files': len(source_files), 'facts': facts_count,
            'own_fact_ids_registered': len(own_ids), 'local_links': links,
            'knowledge_config': active_config, 'scan_entry_count': len(roots),
            'candidate_file_count': len(selected),
            'scan_roots_unchanged': sorted(roots) == sorted(baseline['scan_roots']),
            'candidate_count_matches_baseline': len(selected) == baseline.get('candidate_file_count'),
            'worktree_drift': source_drift, 'errors': errors, 'warnings': warnings,
            'semantic_review': '静态定位检查不能证明事实正确；编写者自审见 reviews/self_review.md，'
                               '独立 Agent 静态审核见 reviews/independent_review_20260919.md（意见已落实到 rev 2）；仍待人工审核',
            'not_checked': '未执行编译、抓包、插件加载、数据库、索引、真实模型与 RAG 对照',
            'documents': previews}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preview-json', action='store_true',
                        help='把完整切分内容输出到 stdout；脚本本身不写文件')
    args = parser.parse_args()
    result = check()
    if args.preview_json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        summary = {k: v for k, v in result.items() if k != 'documents'}
        summary['documents'] = [{'path': d['document_path'], 'chinese_chars': d['chinese_chars'],
                                 'chunks': len(d['chunks']),
                                 'max_chunk_chars': max(c['chars'] for c in d['chunks']),
                                 'chunks_over_window': sum(1 for c in d['chunks'] if c['chars'] > VISIBLE_WINDOW)}
                                for d in result['documents']]
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    return bool(result['errors'])


if __name__ == '__main__':
    raise SystemExit(main())
