"""只读黄金/候选；通过0、业务差分1、输入/schema/manifest损坏2。"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from .compare import compare,Difference,InvalidTrace,validate_trace_schema
from .safety import ROOT


VERSIONS=('p0-v1','p0-v2')


def load_json(path:Path):
    def reject_constant(value):raise InvalidTrace('不允许JSON常量: '+value)
    try:return json.loads(path.read_text(),parse_constant=reject_constant)
    except (OSError,UnicodeError,ValueError,RecursionError) as exc:raise InvalidTrace('JSON读取/解析失败: '+type(exc).__name__) from exc


def load_manifest(folder:Path,version:str):
    manifest=load_json(folder/'manifest.json')
    if type(manifest) is not dict or manifest.get('fixture_id')!=version or type(manifest.get('files')) is not dict:
        raise InvalidTrace('manifest结构或fixture版本不符')
    if 'python-golden.json' not in manifest['files']:raise InvalidTrace('manifest缺黄金摘要')
    for name,digest in manifest['files'].items():
        if type(name) is not str or Path(name).name!=name or type(digest) is not str or len(digest)!=64:
            raise InvalidTrace('manifest文件项损坏或路径越界')
        try:actual=hashlib.sha256((folder/name).read_bytes()).hexdigest()
        except OSError as exc:raise InvalidTrace('manifest文件不可读') from exc
        if actual!=digest:raise InvalidTrace('冻结fixture摘要不符: '+name)
    return manifest


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,required=True)
    parser.add_argument('--fixture',choices=VERSIONS,required=True)
    args=parser.parse_args();folder=ROOT/'migration/fixtures'/args.fixture
    try:
        manifest=load_manifest(folder,args.fixture)
        expected=load_json(folder/'python-golden.json');actual=load_json(args.candidate)
        validate_trace_schema(expected);validate_trace_schema(actual)
        compare(expected,actual)
    except InvalidTrace as exc:
        print(json.dumps({'status':'invalid_manifest_or_input','detail':str(exc)},ensure_ascii=False));return 2
    except Difference as exc:
        print(json.dumps({'status':'difference','detail':str(exc)},ensure_ascii=False));return 1
    print(json.dumps({'status':'pass','fixture':manifest['fixture_id'],'observations':len(actual['observations'])}));return 0

if __name__=='__main__':raise SystemExit(main())
