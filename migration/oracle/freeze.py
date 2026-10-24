"""仅从指定Python trace创建新版本黄金；无候选输入参数，存在即拒绝覆盖。"""
from __future__ import annotations
import argparse
import hashlib
import json
from .compare import compare,normalize,RULES,ID_FORMATS,FLOAT_TOLERANCE_RULES
from .safety import ROOT,BASELINE


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture',choices=['p0-v2'],required=True)
    version=parser.parse_args().fixture
    source=ROOT/f'.migration-artifacts/p0/python-trace-{version}.json'
    folder=ROOT/'migration/fixtures'/version;golden=folder/'python-golden.json';manifest=folder/'manifest.json'
    if golden.exists() or manifest.exists():raise SystemExit('fixture已冻结，拒绝覆盖；扩展须新版本并经owner审阅')
    raw=json.loads(source.read_text())
    if raw.get('baseline_id')!=BASELINE or raw.get('fixture_version')!=version:raise SystemExit('源码/fixture版本不符')
    compare(raw,raw);cleaned=normalize(raw);folder.mkdir(parents=True,exist_ok=True)
    golden.write_text(json.dumps(cleaned,ensure_ascii=False,indent=2)+'\n')
    report=json.loads((ROOT/f'docs/java-migration/evidence/J0-02/auto/p0_python_oracle-{version}.json').read_text())
    (folder/'route-case-map.json').write_text(json.dumps({'version':version,'auth_mode':'account','routes':report['routes']},ensure_ascii=False,indent=2)+'\n')
    paths=[]
    def walk(a,b,path='$'):
        if isinstance(a,dict):
            for k in a:walk(a[k],b[k],f'{path}.{k}')
        elif isinstance(a,list):
            for i,(x,y) in enumerate(zip(a,b)):walk(x,y,f'{path}[{i}]')
        elif a!=b:paths.append({'path':path,'kind':'duration' if b=='<duration>' else 'typed_generated_identity'})
    walk(raw,cleaned)
    (folder/'normalization-review.json').write_text(json.dumps({'version':version,'owner_review':'字段实体格式先校验；全trace双射；UTC逐值；调用者request_id保持；数组不重排','user_acceptance':'未验收','paths':paths,'rules':[r.__dict__ for r in RULES],'identity_formats':ID_FORMATS},ensure_ascii=False,indent=2)+'\n')
    from .business_state import QUERIES
    (folder/'business-projection.json').write_text(json.dumps({'version':'business-v2','queries':QUERIES,'shape':'一次白名单初态+逐case各表removed/added实际业务行+完整终态；可重建每步前置状态','privacy':'不导出密码、token/hash、源码或原始模型输出；消息字段指纹仅用于持久化内容一致性，不替代业务列','checkpoint':'HTTP response consumed and accepted tasks settled; direct transaction group fixed wallclock','retrieval_score_comparison':FLOAT_TOLERANCE_RULES,'other_scores':'citation.round4/业务分数始终exact'},ensure_ascii=False,indent=2)+'\n')
    files=['python-golden.json','route-case-map.json','normalization-review.json','business-projection.json']
    data={'fixture_id':version,'status':'frozen-python-oracle; user acceptance pending','baseline_id':BASELINE,'contract_version':'p0-v1',
          'golden_provenance':'仅从p0_python_oracle冻结Python trace；候选无写入口；p0-v1 rev2保持不变',
          'deterministic_id_port':{'module':'app.services.grading_service.uuid','behavior':'unique repeatable legal UUID v4 objects; frozen grading service still derives grade_16hex','limitation':'does not verify entropy; fixed redaction vectors preserve numeric-run behavior'},
          'files':{f:hashlib.sha256((folder/f).read_bytes()).hexdigest() for f in files},
          'clock':{'HTTP':'2026-10-24T04:00:00+00:00; logical microsecond step','PG_transaction_group':'2026-10-24T04:10:00+00:00 fixed; does not test monotone timestamps'},
          'retrieval_tolerance':FLOAT_TOLERANCE_RULES,'retrieval_order':'exact; semantic ties node_id ASC',
          'verification_command':f'.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture {version} --candidate .migration-artifacts/p0/candidate-trace-{version}.json',
          'approved_java_differences':'../p0-approved-differences/comp003.json remains independent from Python golden',
          'performance_thresholds':{'authority':'migration/contracts/thresholds.json','authorization':'U09/P0-D15','status':'numeric thresholds accepted; P0 phase user acceptance pending'},'live_quality':'live_not_authorized'}
    manifest.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'fixture':version,'files':len(files),'normalized_paths':len(paths),'golden_sha256':data['files']['python-golden.json']}))

if __name__=='__main__':main()
