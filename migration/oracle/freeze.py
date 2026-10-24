"""一次性从指定 Python trace 冻结fixture；存在即拒绝覆盖，无候选输入参数。"""
from __future__ import annotations
import hashlib
import json
from .compare import compare, normalize, RULES
from .safety import ROOT, BASELINE


def main():
    source=ROOT/'.migration-artifacts/p0/python-trace.json'
    golden=ROOT/'migration/fixtures/p0-v1/python-golden.json'
    manifest=golden.with_name('manifest.json')
    if golden.exists() or manifest.exists():raise SystemExit('fixture已冻结，拒绝覆盖；扩展须新版本并经owner审阅')
    raw=json.loads(source.read_text())
    if raw.get('baseline_id')!=BASELINE:raise SystemExit('源码基线不符')
    compare(raw,raw)
    cleaned=normalize(raw)
    golden.write_text(json.dumps(cleaned,ensure_ascii=False,indent=2)+'\n')
    report=json.loads((ROOT/'docs/java-migration/evidence/J0-02/auto/p0_python_oracle.json').read_text())
    mapping=golden.with_name('route-case-map.json')
    mapping.write_text(json.dumps({'version':'p0-v1','auth_mode':'account','coverage':'66路由基础成功/权限/无效输入；适用重试并发按项披露，非全功能验收','routes':report['routes']},ensure_ascii=False,indent=2)+'\n')
    paths=[]
    def walk(a,b,path='$'):
        if isinstance(a,dict):
            for k in a:walk(a[k],b[k],f'{path}.{k}')
        elif isinstance(a,list):
            for i,(x,y) in enumerate(zip(a,b)):walk(x,y,f'{path}[{i}]')
        elif a!=b:paths.append({'path':path,'kind':'duration' if b=='<duration>' else 'generated_identity','reason':'生成实体按全trace双射；调用方request_id保留；耗时非负整数'})
    walk(raw,cleaned)
    normalization=golden.with_name('normalization-review.json')
    normalization.write_text(json.dumps({'version':'p0-v1','owner_review':'已审阅：仅列出实际被归一化的字段路径；时间逐值/UTC/精度/时序校验；未作数组重排','user_acceptance':'未验收','paths':paths,'rules':[r.__dict__ for r in RULES]},ensure_ascii=False,indent=2)+'\n')
    files=['python-golden.json','route-case-map.json','normalization-review.json','upstream-scripts.json','knowledge.yml']
    data={'fixture_id':'p0-v1','status':'frozen-for-python-differential; user acceptance pending',
          'baseline_id':BASELINE,'contract_version':'p0-v1','golden_provenance':'仅由冻结Python在专用p0_python_oracle采集；p0_candidate_oracle是第二独立库Python自对照，尚无Java',
          'clock':{'start':'2026-10-24T04:00:00+00:00','logical_step_microseconds':1,'postgres_session_timezone':'UTC','duration_clock':'原始monotonic，不归零'},
          'data':'纯合成账号/作业/release/历史引用/失败任务；运行时源码标记不进fixture',
          'files':{f:hashlib.sha256((golden.parent/f).read_bytes()).hexdigest() for f in files},
          'verification_command':'.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --candidate .migration-artifacts/p0/candidate-trace.json',
          'performance_thresholds':'pending_measurement_and_user_confirmation','live_quality':'live_not_authorized'}
    manifest.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'fixture':'p0-v1','files':len(files),'normalized_paths':len(paths),'golden_sha256':data['files']['python-golden.json']}))

if __name__=='__main__':main()
