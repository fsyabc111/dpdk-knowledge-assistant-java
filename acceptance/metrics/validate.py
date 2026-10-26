#!/usr/bin/env python3
"""验证真实Java Prometheus文本的既有语义映射；不修改黄金/strict比较或豁免。"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re

class MetricsError(ValueError):pass

METHODS={'GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS','OTHER'}
DEFINITIONS={
 'assistant_java_http_requests_total':('counter',{'method','status'}),
 'assistant_java_http_dispatch_duration_seconds':('summary',{'method','status'}),
 'assistant_java_domain_metric_available':('gauge',{'metric'}),
 'assistant_java_persisted_task_records':('gauge',{'status','reason'}),
 'assistant_rag_outcomes_total':('gauge',{'status','reason'}),
 'assistant_java_persisted_task_elapsed_seconds_sum':('gauge',{'status','reason'}),
 'assistant_java_persisted_job_records':('gauge',{'tool','status'}),
 'assistant_java_persisted_usage_records':('gauge',{'stage','kind','source'}),
 'assistant_java_persisted_usage_calls':('gauge',{'stage','kind','source'}),
 'assistant_upstream_usage_tokens_total':('gauge',{'stage','kind','source'}),
 'assistant_upstream_usage_cost_micros_total':('gauge',{'stage','kind','model','currency'}),
 'assistant_upstream_usage_unknown_total':('gauge',{'stage','kind'}),
 'assistant_java_usage_token_unknown_records':('gauge',{'stage','kind','source'}),
 'assistant_java_usage_cost_unknown_records':('gauge',{'stage','kind','source'}),
 'assistant_java_persisted_usage_duration_seconds_sum':('gauge',{'stage','kind','source'}),
}
NAME=re.compile(r'[A-Za-z_:][A-Za-z0-9_:]*')
LABEL=re.compile(r'([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\[\\"n])*)"')

@dataclass(frozen=True)
class Sample:
    name:str
    labels:tuple
    value:float
    def identity(self):return (self.name,self.labels)

def labels(text):
    result={};position=0
    while position<len(text):
        match=LABEL.match(text,position)
        if not match:raise MetricsError('invalid label syntax')
        key,value=match.groups()
        if key in result:raise MetricsError('duplicate label')
        value=re.sub(r'\\([\\"n])',lambda x:'\n' if x[1]=='n' else x[1],value)
        result[key]=value;position=match.end()
        if position==len(text):break
        if text[position]!=',':raise MetricsError('invalid label separator')
        position+=1
        if position==len(text):raise MetricsError('trailing label comma')
    return tuple(sorted(result.items()))

def parse(text):
    types={};samples={}
    for number,line in enumerate(text.splitlines(),1):
        line=line.strip()
        if not line:continue
        if line.startswith('# TYPE '):
            parts=line.split()
            if len(parts)!=4 or not NAME.fullmatch(parts[2]) or parts[3] not in ('counter','gauge','summary','histogram','untyped'):raise MetricsError('invalid TYPE declaration')
            if parts[2] in types:raise MetricsError('duplicate TYPE declaration')
            types[parts[2]]=parts[3];continue
        if line.startswith('#'):continue
        match=NAME.match(line)
        if not match:raise MetricsError('invalid sample name')
        name=match[0];tail=line[match.end():];parsed_labels=()
        if tail.startswith('{'):
            quoted=False;escaped=False;end=None
            for index,char in enumerate(tail[1:],1):
                if escaped:escaped=False;continue
                if quoted and char=='\\':escaped=True;continue
                if char=='"':quoted=not quoted
                if char=='}' and not quoted:end=index;break
            if end is None:raise MetricsError('unclosed labels')
            parsed_labels=labels(tail[1:end]);tail=tail[end+1:]
        if not tail or not tail[0].isspace():raise MetricsError('sample missing separator')
        parts=tail.split()
        if len(parts) not in (1,2):raise MetricsError('invalid sample fields')
        try:value=float(parts[0])
        except ValueError as error:raise MetricsError('invalid numeric value') from error
        if not math.isfinite(value) or value<0:raise MetricsError('metric must be finite nonnegative')
        if len(parts)==2 and not re.fullmatch(r'-?\d+',parts[1]):raise MetricsError('invalid timestamp')
        sample=Sample(name,parsed_labels,value)
        if sample.identity() in samples:raise MetricsError('duplicate metric series')
        samples[sample.identity()]=sample
    return types,samples

def validate(text,*,expectations=None,status=200,media_type='text/plain'):
    if status!=200 or media_type.split(';',1)[0].strip()!='text/plain':raise MetricsError('HTTP status/media type mismatch')
    mapping=json.loads(Path(__file__).with_name('mapping.json').read_text());required=set(mapping['required_domain_unavailable'])
    types,samples=parse(text)
    for name,kind in types.items():
        if name not in DEFINITIONS:raise MetricsError('undocumented Java family: '+name)
        if kind!=DEFINITIONS[name][0]:raise MetricsError('wrong TYPE: '+name)
    for mandatory in ('assistant_java_http_requests_total','assistant_java_http_dispatch_duration_seconds','assistant_java_domain_metric_available'):
        if mandatory not in types:raise MetricsError('missing required Java TYPE')
    for sample in samples.values():
        family=sample.name
        if family not in types and sample.name.endswith(('_count','_sum')):
            family=sample.name.rsplit('_',1)[0]
            if types.get(family)!='summary':raise MetricsError('sample has no declared summary TYPE')
        if family not in types:raise MetricsError('sample has no TYPE')
        if set(dict(sample.labels))!=DEFINITIONS[family][1]:raise MetricsError('unsafe/missing label dimensions')
        dims=dict(sample.labels)
        if family.startswith('assistant_java_http_'):
            if dims['method'] not in METHODS or not re.fullmatch(r'[1-5]\d\d',dims['status']):raise MetricsError('unbounded HTTP method/status')
        if family=='assistant_rag_outcomes_total' and dims['status']=='pending':raise MetricsError('pending is not a terminal outcome')
    absent_markers={}
    for sample in samples.values():
        if sample.name=='assistant_java_domain_metric_available':
            metric=dict(sample.labels)['metric'];absent_markers[metric]=sample.value
            if metric not in required or sample.value!=0:raise MetricsError('invalid unavailable marker')
    if set(absent_markers)!=required:raise MetricsError('missing unavailable0 markers')
    if required & types.keys():raise MetricsError('unavailable observation has fabricated source family')
    # HTTP请求次数必须与同一dispatch summary count一致；不声称sum等价源histogram。
    for sample in samples.values():
        if sample.name=='assistant_java_http_requests_total':
            count=samples.get(('assistant_java_http_dispatch_duration_seconds_count',sample.labels))
            total=samples.get(('assistant_java_http_dispatch_duration_seconds_sum',sample.labels))
            if count is None or total is None or count.value!=sample.value:raise MetricsError('HTTP count/summary inconsistent')
    request_keys={sample.labels for sample in samples.values() if sample.name=='assistant_java_http_requests_total'}
    for suffix in ('_count','_sum'):
        summary_keys={sample.labels for sample in samples.values() if sample.name=='assistant_java_http_dispatch_duration_seconds'+suffix}
        if request_keys!=summary_keys:raise MetricsError('HTTP summary has orphan/missing series')
    # 同时存在的账本行数与unknown指标需一致；独立已知种子预期另由expectations检查。
    unknown_rows={}
    for sample in samples.values():
        dims=dict(sample.labels)
        if sample.name=='assistant_java_persisted_usage_records' and dims['source']=='unknown':
            key=tuple(sorted({'stage':dims['stage'],'kind':dims['kind']}.items()));unknown_rows[key]=unknown_rows.get(key,0)+sample.value
    for key,value in unknown_rows.items():
        observed=samples.get(('assistant_upstream_usage_unknown_total',key))
        if value>0 and (observed is None or observed.value!=value or observed.value==0):raise MetricsError('unknown usage suppressed or zero-filled')
    for sample in samples.values():
        if sample.name=='assistant_upstream_usage_unknown_total':
            if sample.value<=0 or sample.labels not in unknown_rows or unknown_rows[sample.labels]!=sample.value:
                raise MetricsError('unknown counter lacks matching positive persisted rows')
    checks=0
    expectations=expectations or {}
    for expected in expectations.get('samples',[]):
        key=(expected['name'],tuple(sorted(expected.get('labels',{}).items())))
        observed=samples.get(key)
        if observed is None or not math.isclose(observed.value,expected['value'],rel_tol=1e-12,abs_tol=1e-12):raise MetricsError('known seeded value mismatch: '+expected['name'])
        checks+=1
    for expected in expectations.get('absent_samples',[]):
        key=(expected['name'],tuple(sorted(expected.get('labels',{}).items())))
        if key in samples:raise MetricsError('unknown NULL was converted to an emitted sample')
        checks+=1
    for family in expectations.get('exact_series_families',[]):
        actual={sample.identity() for sample in samples.values() if sample.name==family}
        expected={(entry['name'],tuple(sorted(entry.get('labels',{}).items()))) for entry in expectations.get('samples',[]) if entry['name']==family}
        if actual!=expected:raise MetricsError('unexpected seeded family series: '+family)
        checks+=1
    return {'status':'passed_with_declared_coverage','family_count':len(types),'series_count':len(samples),'unavailable0_count':len(absent_markers),
        'known_value_checks':checks,'known_values_domain':expectations.get('domain','not_supplied'),'validation_scope':'structure_plus_explicit_known_values' if checks else 'structure_and_present_series_consistency_only','positive_unknown_groups':sum(value>0 for value in unknown_rows.values()),
        'unknown_positive_http_validation':'exercised' if any(value>0 for value in unknown_rows.values()) else 'not_exercised_no_positive_unknown_rows_in_wire',
        'source_families':mapping['source_family_count'],'raw_wire_equivalence':False,'strict_verdict_unchanged':True,
        'body_sha256':hashlib.sha256(text.encode()).hexdigest()}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--wire',type=Path,required=True);parser.add_argument('--expectations',type=Path)
    parser.add_argument('--status',type=int,default=200);parser.add_argument('--media-type',default='text/plain');args=parser.parse_args()
    try:
        expected=json.loads(args.expectations.read_text()) if args.expectations else None
        print(json.dumps(validate(args.wire.read_text(),expectations=expected,status=args.status,media_type=args.media_type),ensure_ascii=False))
    except MetricsError as error:print(json.dumps({'status':'rejected','reason':str(error)},ensure_ascii=False));raise SystemExit(1)
