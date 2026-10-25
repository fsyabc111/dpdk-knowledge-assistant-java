#!/usr/bin/env python3
"""按冻结manifest逐source/include生成全合成知识源；不读取任何原项目源码。"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re


def source_entries(text):
    result, current, includes = [], None, False
    for raw in text.splitlines():
        line = raw.split('#', 1)[0].rstrip()
        if not line.strip():
            continue
        match = re.fullmatch(r'  - name: ([A-Za-z0-9_]+)', line)
        if match:
            current = {'name': match[1], 'include': []}; result.append(current); includes = False
        elif match := re.fullmatch(r'    root: (\$\{(?:DPDK_REPO_PATH|ASSISTANT_PROJECT_PATH)\})', line):
            if current is None: raise ValueError('root缺少source')
            current['root'] = 'dpdk' if match[1] == '${DPDK_REPO_PATH}' else 'assistant'
        elif line == '    include:': includes = True
        elif match := re.fullmatch(r'      - ([A-Za-z0-9_./-]+)', line):
            if current is None or not includes: raise ValueError('include缺少source')
            path = Path(match[1])
            if path.is_absolute() or '..' in path.parts: raise ValueError('include越界')
            current['include'].append(match[1])
        elif line != 'sources:': raise ValueError('manifest格式超出受支持冻结子集')
    if not result or any('root' not in item or not item['include'] for item in result):
        raise ValueError('source/root/include不得缺失')
    return result


def generate(manifest, destination):
    manifest = Path(manifest).resolve(strict=True); destination = Path(destination)
    if not destination.is_absolute() or destination.exists(): raise ValueError('输出必须为尚不存在的绝对目录')
    text = manifest.read_text(); entries = source_entries(text)
    destination.mkdir(parents=True, mode=0o755)
    (destination/'dpdk').mkdir(); (destination/'assistant').mkdir()
    coverage, files = [], {}
    for entry in entries:
        for index, include in enumerate(entry['include']):
            relative = include + f'synthetic_{entry["name"]}_{index}.md' if include.endswith('/') else include
            path = destination/entry['root']/relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists():
                body = f'''合成隔离知识资料 SYNTHETIC_J5_03_ONLY
路径：{entry['root']}/{relative}
此内容完全人工构造，只用于Java隔离部署与全源索引验收，不是原项目文档或源码。
DNS协议：固定示例由合成请求和合成答案组成。HTTP与SSH条目同样不代表真实协议质量。
验收要求：来源路径、引用、历史版本与索引CAS发布必须可追溯；不得以空索引视为成功。
'''
                if path.suffix in ('.c','.h'): body = '/*\n'+body+'*/\n'
                elif path.suffix == '.py': body = '\n'.join('# '+line for line in body.splitlines())+'\n'
                path.write_text(body); path.chmod(0o644)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            key = str(path.relative_to(destination)); files[key] = digest
            coverage.append({'source':entry['name'],'root':entry['root'],'include':include,'kind':'directory' if include.endswith('/') else 'file','generated':key})
    (destination/'knowledge_sources.yml').write_text(text)
    result = {'synthetic':True,'version':1,'original_manifest_sha256':hashlib.sha256(text.encode()).hexdigest(),
        'source_count':len(entries),'include_count':len(coverage),'file_count':len(files),
        'roots':{'dpdk':'dpdk','assistant':'assistant'},'coverage':coverage,'files':dict(sorted(files.items()))}
    (destination/'source-snapshot.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',required=True,type=Path); parser.add_argument('--output',required=True,type=Path)
    args = parser.parse_args(); result = generate(args.manifest,args.output)
    print(json.dumps({key:result[key] for key in ('synthetic','source_count','include_count','file_count','original_manifest_sha256')}))
