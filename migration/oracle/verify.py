"""差分CLI：校验冻结manifest并比较候选；差异返回1，清单/输入错误返回2。"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from .compare import compare,Difference
from .safety import ROOT


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,required=True)
    args=parser.parse_args();folder=ROOT/'migration/fixtures/p0-v1'
    try:
        manifest=json.loads((folder/'manifest.json').read_text())
        for name,digest in manifest['files'].items():
            if Path(name).name!=name:raise ValueError('manifest路径不能离开fixture')
            if hashlib.sha256((folder/name).read_bytes()).hexdigest()!=digest:raise ValueError('冻结fixture摘要不符: '+name)
        expected=json.loads((folder/'python-golden.json').read_text());actual=json.loads(args.candidate.read_text())
    except (OSError,ValueError,KeyError) as exc:
        print(json.dumps({'status':'invalid_manifest_or_input','detail':str(exc)},ensure_ascii=False));return 2
    try:compare(expected,actual)
    except Difference as exc:
        print(json.dumps({'status':'difference','detail':str(exc)},ensure_ascii=False));return 1
    print(json.dumps({'status':'pass','fixture':manifest['fixture_id'],'observations':len(actual['observations'])}));return 0

if __name__=='__main__':raise SystemExit(main())
