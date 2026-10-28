"""严格单case策略：仅GET /metrics::success的family descriptor按唯一name→TYPE比较。

原golden、原candidate和migration.oracle.compare保持不变；除该字段以外完整trace
仍由原compare验证。样本/bucket/labels调用正式source-process-v2 validator；过程hook
因果证据和独立两轴审查必须另行成立，不从本case的TYPE注册推导。
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import importlib
import json
import math
from pathlib import Path
import re
import sys
from migration.oracle.compare import compare,Difference,InvalidTrace
from migration.oracle.verify import load_manifest,load_json

ROOT=Path(__file__).resolve().parents[2]
METRICS=ROOT/'acceptance/metrics'
CASE='GET /metrics::success'
POLICY='J5-01-single-metrics-schema-map-v1'
OFFICIAL='https://prometheus.io/docs/instrumenting/exposition_formats/#grouping-and-sorting'
NAME=r'[A-Za-z_:][A-Za-z0-9_:]*'
TYPE=re.compile(r'\s*#\s+TYPE\s+('+NAME+r')\s+(counter|gauge|histogram|summary|untyped)\s*')
HELP=re.compile(r'\s*#\s+HELP\s+('+NAME+r')(?:\s+.*)?')

class PolicyError(ValueError):pass

def process_validator():
    # 正式v2模块复用其通用Prom parser；不调用persistent_diagnostic.validate。
    path=str(METRICS);sys.path.insert(0,path)
    try:module=importlib.import_module('acceptance.metrics.validate')
    finally:sys.path.remove(path)
    if Path(module.__file__).resolve()!=METRICS/'validate.py':raise PolicyError('wrong formal validator module')
    return module

def descriptor_map(lines):
    if type(lines) is not list:raise PolicyError('families必须为descriptor数组')
    result={}
    for line in lines:
        if type(line) is not str:raise PolicyError('descriptor必须是string')
        m=TYPE.fullmatch(line)
        if not m:raise PolicyError('invalid TYPE descriptor')
        name,kind=m.groups()
        if name in result:raise PolicyError('duplicate TYPE descriptor: '+name)
        result[name]=kind
    return result

def case(trace):
    hits=[o for o in trace.get('observations',[]) if o.get('case_id')==CASE]
    if len(hits)!=1 or hits[0].get('route_id')!='GET /metrics':raise PolicyError('exact metrics case missing/duplicate/wrong route')
    if type(hits[0].get('body')) is not dict or set(hits[0]['body'])!={'families'}:raise PolicyError('metrics body keys changed')
    return hits[0]

def wire_groups(text):
    """保留组内原始顺序/值；只允许完整独立metric组的排序变化。"""
    if not text.endswith('\n'):raise PolicyError('Prometheus末行必须LF结束')
    module=process_validator();types,_=module.parse(text)
    help_seen=set();type_seen=set();sample_seen=set();groups={};closed=set();current=None;bucket_last={}
    for original in text.splitlines():
        line=original.strip()
        if not line:continue
        t=TYPE.fullmatch(line);h=HELP.fullmatch(line)
        sample=None;name=None
        if t:
            name=t.group(1)
            if name in type_seen:raise PolicyError('duplicate TYPE descriptor')
            type_seen.add(name)
        elif h:
            name=h.group(1)
            if name in help_seen:raise PolicyError('duplicate HELP descriptor')
            help_seen.add(name)
            if name not in types:raise PolicyError('HELP lacks typed family')
        elif line.startswith('#'):
            if re.match(r'#\s+(TYPE|HELP)(?:\s|$)',line):raise PolicyError('malformed metric metadata')
            continue  # 非HELP/TYPE注释无度量语义；不接收任意metric样本到无TYPE组。
        else:
            m=re.match(NAME,line)
            if not m:raise PolicyError('unknown sample syntax')
            emitted=m.group(0);name=emitted
            if name not in types:
                matches=[emitted[:-len(s)] for s in ('_bucket','_count','_sum') if emitted.endswith(s) and emitted[:-len(s)] in types]
                if len(matches)!=1:raise PolicyError('sample lacks unique typed family')
                name=matches[0]
            if name not in type_seen:raise PolicyError('TYPE must precede samples')
            _,one=module.parse('# TYPE '+name+' '+types[name]+'\n'+line+'\n')
            sample=next(iter(one.values()));sample_seen.add(name)
            if emitted.endswith('_bucket'):
                labels=dict(sample.labels)
                if 'le' not in labels:raise PolicyError('bucket lacks le')
                try:bound=float(labels.pop('le'))
                except ValueError as e:raise PolicyError('invalid bucket boundary') from e
                if math.isnan(bound):raise PolicyError('NaN bucket')
                key=name,tuple(sorted(labels.items()))
                if bound<=bucket_last.get(key,-math.inf):raise PolicyError('bucket numeric order changed')
                bucket_last[key]=bound
        if (t or h) and name in sample_seen:raise PolicyError('metadata after samples')
        if name!=current:
            if name in closed:raise PolicyError('metric family is not one contiguous group')
            if current is not None:closed.add(current)
            current=name
        groups.setdefault(name,[]).append(original)
    if set(groups)!=set(types):raise PolicyError('wire group/TYPE mismatch')
    return types,groups

def canonical_wire(text):
    types,groups=wire_groups(text);module=process_validator();order=[]
    for section in ('jvm','domain','http'):
        for row in module.SPEC[section]:
            order.append(row['name'])
            if section!='jvm':
                created=row['name'][:-6]+'_created' if row['type']=='counter' else row['name']+'_created'
                if created in types:order.append(created)
    if set(order)!=set(types):raise PolicyError('unknown/missing formal family')
    return '\n'.join(line for name in order for line in groups[name])+'\n'

def compare_with_policy(expected,actual,wire):
    # 先执行原始验证，损坏schema/时间/业务链/SSE不能通过单case策略。
    try:compare(expected,actual);raw={'status':'pass'}
    except Difference as e:raw={'status':'difference','detail':str(e)}
    e,a=case(expected),case(actual);et=descriptor_map(e['body']['families']);at=descriptor_map(a['body']['families'])
    mapping=json.loads((METRICS/'mapping.json').read_text());module=process_validator()
    source_runtime={r['source_family']:r['source_type'] for r in mapping['rows'] if r['source_group']=='python_runtime'}
    shared={r['source_family']:r['source_type'] for r in mapping['rows'] if r['source_group']!='python_runtime'}
    jvm={r['name']:r['type'] for r in module.SPEC['jvm']}
    if len(shared)!=30 or len(source_runtime)!=4 or len(jvm)!=4:raise PolicyError('declared 30+4 policy schema changed')
    if et!={**source_runtime,**shared}:raise PolicyError('expected frozen source schema changed')
    if at!={**jvm,**shared}:raise PolicyError('unknown/missing family or wrong TYPE')
    wt,_=wire_groups(wire)
    if wt!=at:raise PolicyError('candidate descriptors do not match actual wire')
    canonical=canonical_wire(wire)
    validation=module.validate(canonical,status=a['status'],media_type=a['media_type'])
    # 仅替换此明确字段的内存副本；schema来自冻结source，而非Java输出反生期望。
    left,right=copy.deepcopy(expected),copy.deepcopy(actual)
    normalized_field={'domain_http_created':shared,'runtime_mapping_policy':'source-process-v2-real-jvm-capabilities'}
    case(left)['body']['families']=copy.deepcopy(normalized_field)
    case(right)['body']['families']=copy.deepcopy(normalized_field)
    compare(left,right)  # 其他全部字段、数组/引用顺序、状态及全trace关联仍走原比较器。
    return {'status':'contract_equal_under_single_explicit_metrics_policy','policy':POLICY,'case_id':CASE,
        'only_transformed_path':'observations[case_id=GET /metrics::success].body.families','source_shared_families':30,
        'source_runtime':source_runtime,'actual_jvm_runtime':jvm,'runtime_note':'不同语言真实能力映射，不声明对象量/代际/collector等价',
        'raw_original_compare':raw,'raw_wire_sha256':hashlib.sha256(wire.encode()).hexdigest(),
        'metric_group_order_canonical_sha256':hashlib.sha256(canonical.encode()).hexdigest(),
        'formal_v2_validation':validation,'business_hook_causality':'independent_evidence_required_not_inferred_from_TYPE',
        'stage_gate':'independent_two_axis_review_and_main_agent_decision_required','official_spec':OFFICIAL}

def main():
    p=argparse.ArgumentParser();p.add_argument('--candidate',required=True,type=Path);p.add_argument('--wire',required=True,type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
    try:
        load_manifest(ROOT/'migration/fixtures/p0-v2','p0-v2')
        result=compare_with_policy(load_json(ROOT/'migration/fixtures/p0-v2/python-golden.json'),load_json(a.candidate),a.wire.read_text())
    except (PolicyError,Difference,InvalidTrace,ValueError,OSError) as e:
        result={'status':'rejected','policy':POLICY,'reason':str(e)};code=1
    else:code=0
    a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False));return code
if __name__=='__main__':raise SystemExit(main())
