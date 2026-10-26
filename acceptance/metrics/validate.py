#!/usr/bin/env python3
"""正式source过程指标验收：真实counter/histogram与源bucket；拒绝持久gauge/unavailable替代。"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
from persistent_diagnostic import MetricsError,parse

SPEC=json.loads(Path(__file__).with_name('process-spec.json').read_text())
BASE={row['name']:row for group in ('domain','http','jvm') for row in SPEC[group]}
CREATED={}
for row in SPEC['domain']+SPEC['http']:
    name=row['name'][:-6]+'_created' if row['type']=='counter' else row['name']+'_created'
    CREATED[name]=row

def series_key(name,labels):return name,tuple(sorted(labels.items()))
def without_le(labels):return tuple((key,value) for key,value in labels if key!='le')
def near(left,right):return math.isclose(left,right,rel_tol=1e-12,abs_tol=1e-12)

def validate(text,*,expectations=None,status=200,media_type='text/plain'):
    if status!=200 or media_type.split(';',1)[0].strip()!='text/plain':raise MetricsError('HTTP status/media type mismatch')
    types,samples=parse(text)
    expected_types={name:row['type'] for name,row in BASE.items()}
    expected_types.update({name:'gauge' for name in CREATED})
    if not BASE.keys()<=types.keys():raise MetricsError('missing registered source process/HTTP/JVM family')
    for name,kind in types.items():
        if name not in expected_types:raise MetricsError('unexpected family: no fake Python runtime or persisted/unavailable substitute')
        if kind!=expected_types[name]:raise MetricsError('source TYPE mismatch: '+name)
    # 注册顺序按源：runtime映射四项→16领域→五HTTP；created随其真正child出现。
    order=[]
    for group in ('jvm','domain','http'):
        for row in SPEC[group]:
            order.append(row['name'])
            if group!='jvm':
                created=row['name'][:-6]+'_created' if row['type']=='counter' else row['name']+'_created'
                if created in types:order.append(created)
    if list(types)!=order:raise MetricsError('source family registration order mismatch')
    children={name:{} for name in BASE}
    metadata={name:{} for name in CREATED}
    for sample in samples.values():
        name=sample.name;suffix=None
        if name in CREATED:
            row=CREATED[name]
            if set(dict(sample.labels))!=set(row['labels']):raise MetricsError('created labels invalid')
            metadata[name][sample.labels]=sample.value;continue
        family=name
        if family not in BASE:
            for ending in ('_bucket','_count','_sum'):
                candidate=name[:-len(ending)] if name.endswith(ending) else ''
                if candidate in BASE and BASE[candidate]['type'] in ('histogram','summary'):
                    family=candidate;suffix=ending;break
        if family not in BASE:raise MetricsError('sample lacks source family')
        row=BASE[family];kind=row['type'];dims=dict(sample.labels)
        expected=set(row['labels'])|({'le'} if suffix=='_bucket' else set())
        if set(dims)!=expected:raise MetricsError('unsafe/missing label dimensions')
        if any(any(ord(char)<32 or ord(char)==127 for char in value) for value in dims.values()):raise MetricsError('control content in metric label')
        if kind in ('histogram','summary') and suffix is None:raise MetricsError('unsupported direct histogram/summary sample')
        if suffix=='_bucket' and kind!='histogram':raise MetricsError('summary cannot invent histogram buckets')
        if family.startswith('http_'):
            if 'status' in dims and (len(dims['status'])!=3 or dims['status'][0] not in '12345' or dims['status'][1:]!='xx'):
                raise MetricsError('HTTP status must be source grouped Nxx')
            if 'handler' in dims and ('\n' in dims['handler'] or '\r' in dims['handler'] or len(dims['handler'])>256):raise MetricsError('unsafe route label')
        child=children[family].setdefault(without_le(sample.labels),{})
        slot=dims['le'] if suffix=='_bucket' else suffix or 'value'
        if slot in child:raise MetricsError('duplicate metric component')
        child[slot]=sample.value
    observed=[]
    for name,row in BASE.items():
        if name.startswith('jvm_'):continue
        kind=row['type'];childmap=children[name]
        created=name[:-6]+'_created' if kind=='counter' else name+'_created'
        if not row['labels'] and set(childmap)!={()}:raise MetricsError('unlabeled source instance is missing')
        if childmap:
            if created not in types or set(metadata[created])!=set(childmap):raise MetricsError('actual child creation metadata missing')
        elif created in types or metadata[created]:raise MetricsError('unobserved labeled family has fabricated created metadata')
        for labels,values in childmap.items():
            if kind=='counter':
                if set(values)!={'value'}:raise MetricsError('counter sample components invalid')
                if values['value']!=int(values['value']):raise MetricsError('source event counter must be integral')
                zero_cost_child=name=='assistant_upstream_usage_cost_micros_total' and bool(row['labels'])
                if row['labels'] and values['value']==0 and not zero_cost_child:raise MetricsError('unobserved labeled event counter was fabricated at zero')
                if values['value']>0 or zero_cost_child:observed.append(name)
                continue
            if '_count' not in values or '_sum' not in values:raise MetricsError('histogram/summary count or sum absent')
            count=values['_count']
            if count!=int(count):raise MetricsError('observation count must be integral')
            if kind=='histogram':
                bounds=[str(float(bound)) for bound in row['buckets']]+['+Inf']
                if set(values)!=set(bounds)|{'_count','_sum'}:raise MetricsError('frozen histogram bucket set changed')
                cumulative=-1
                for bound in bounds:
                    value=values[bound]
                    if value!=int(value) or value<cumulative or value>count:raise MetricsError('noncumulative/invalid histogram buckets')
                    cumulative=value
                if values['+Inf']!=count:raise MetricsError('+Inf bucket and count differ')
            elif set(values)!={'_count','_sum'}:raise MetricsError('source summary has unexpected components')
            if count>0:observed.append(name)
    if len(children['jvm_info'])!=1 or any(values.get('value')!=1 for values in children['jvm_info'].values()):raise MetricsError('JVM info must reflect one actual JVM')
    expectations=expectations or {};checks=0
    for expected in expectations.get('samples',[]):
        sample=samples.get(series_key(expected['name'],expected.get('labels',{})))
        if sample is None or not near(sample.value,expected['value']):raise MetricsError('known true event value mismatch: '+expected['name'])
        checks+=1
    for expected in expectations.get('absent_samples',[]):
        if series_key(expected['name'],expected.get('labels',{})) in samples:raise MetricsError('unknown/zero usage was fabricated into sample')
        checks+=1
    for family in expectations.get('exact_series_families',[]):
        actual={s.identity() for s in samples.values() if s.name==family}
        expected={series_key(row['name'],row.get('labels',{})) for row in expectations.get('samples',[]) if row['name']==family}
        if actual!=expected:raise MetricsError('unexpected exact family child')
        checks+=1
    if not set(expectations.get('required_observed_families',[]))<=set(observed):raise MetricsError('registered TYPE lacks required real observations')
    return {'status':'source_process_structure_and_declared_events_passed','version':SPEC['version'],'family_count':len(types),'series_count':len(samples),
            'domain_families_registered':16,'domain_families_observed':len(set(observed)&{row['name'] for row in SPEC['domain']}),
            'known_event_checks':checks,'known_event_domain':expectations.get('domain','not_supplied_no_hook_claim'),
            'jvm_runtime_mapping':'actual_JVM_capabilities_not_Python_object_counts','persisted_gauge_or_unavailable_used':False,
            'strict_verdict_unchanged':True,'raw_wire_equivalence':False,'body_sha256':hashlib.sha256(text.encode()).hexdigest()}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--wire',type=Path,required=True);parser.add_argument('--expectations',type=Path)
    parser.add_argument('--status',type=int,default=200);parser.add_argument('--media-type',default='text/plain');args=parser.parse_args()
    try:
        print(json.dumps(validate(args.wire.read_text(),expectations=json.loads(args.expectations.read_text()) if args.expectations else None,status=args.status,media_type=args.media_type),ensure_ascii=False))
    except MetricsError as error:print(json.dumps({'status':'rejected','reason':str(error)},ensure_ascii=False));raise SystemExit(1)
