"""单case政策的纯反例；人工schema样本不冒充真实JVM或业务hook。"""
import copy
import json
from pathlib import Path
import unittest
from acceptance.contract.metrics_case import (ROOT,CASE,compare_with_policy,descriptor_map,wire_groups,PolicyError)
from migration.oracle.compare import Difference

def checkpoint_wire():
    spec=json.loads((ROOT/'acceptance/metrics/process-spec.json').read_text());lines=[]
    def sample(name,labels,value):
        tags='{'+','.join(k+'="'+v+'"' for k,v in labels.items())+'}' if labels else ''
        lines.append(name+tags+' '+str(float(value)))
    for row in spec['jvm']:
        name=row['name'];lines.append('# TYPE '+name+' '+row['type'])
        labels={key:'synthetic' for key in row['labels']};sample(name,labels,1)
    for group in ('domain','http'):
        for row in spec[group]:
            name,kind=row['name'],row['type'];lines.append('# TYPE '+name+' '+kind)
            if group=='domain' and row['labels']:continue
            labels={k:{'method':'GET','status':'2xx','handler':'/api/health'}[k] for k in row['labels']}
            count=0 if group=='domain' else 1
            if kind=='counter':sample(name,labels,count)
            else:
                if kind=='histogram':
                    for bound in row['buckets']:sample(name+'_bucket',{**labels,'le':str(float(bound))},count)
                    sample(name+'_bucket',{**labels,'le':'+Inf'},count)
                sample(name+'_count',labels,count);sample(name+'_sum',labels,0)
            created=name[:-6]+'_created' if kind=='counter' else name+'_created'
            lines.append('# TYPE '+created+' gauge');sample(created,labels,1000)
    return '\n'.join(lines)+'\n'

def fixtures():
    golden=json.loads((ROOT/'migration/fixtures/p0-v2/python-golden.json').read_text())
    metric=next(o for o in golden['observations'] if o['case_id']==CASE)
    product={'case_id':'synthetic-product','route_id':'POST /api/chat','status':200,'media_type':'application/json',
        'body':{'score':80,'role':'viewer','rows':[{'user_id':'U20270000011','score':80},{'user_id':'U20270000021','score':80}],
                'task_id':'a'*32,'session_id':'sess_'+'b'*12}}
    lookup={'case_id':'synthetic-task','route_id':'GET /api/tasks/{task_id}','status':200,'media_type':'application/json',
        'request':{'path_parameters':{'task_id':'a'*32}},'body':{'task_id':'a'*32}}
    expected={'observations':[copy.deepcopy(metric),product,lookup]}
    actual=copy.deepcopy(expected);wire=checkpoint_wire();actual['observations'][0]['body']['families']=[line for line in wire.splitlines() if line.startswith('# TYPE ')]
    return expected,actual,wire

class MetricCaseTests(unittest.TestCase):
    def setUp(self):self.g,self.a,self.wire=fixtures()
    def reject(self,mutate,wire=None):
        mutate(self.a)
        with self.assertRaises((PolicyError,Difference,ValueError)):compare_with_policy(self.g,self.a,self.wire if wire is None else wire)
    def test_single_mapping_control(self):
        result=compare_with_policy(self.g,self.a,self.wire)
        self.assertEqual(30,result['source_shared_families']);self.assertEqual('difference',result['raw_original_compare']['status'])
        self.assertEqual('independent_evidence_required_not_inferred_from_TYPE',result['business_hook_causality'])
    def test_complete_independent_groups_can_change_order(self):
        _,groups=wire_groups(self.wire);wire='\n'.join(line for name in reversed(list(groups)) for line in groups[name])+'\n'
        self.a['observations'][0]['body']['families'].reverse()
        self.assertEqual('contract_equal_under_single_explicit_metrics_policy',compare_with_policy(self.g,self.a,wire)['status'])
    def test_duplicate_descriptor_even_same_type_rejected(self):
        self.reject(lambda x:x['observations'][0]['body']['families'].append(x['observations'][0]['body']['families'][0]))
    def test_unknown_family_rejected(self):
        self.reject(lambda x:x['observations'][0]['body']['families'].append('# TYPE surprise_total counter'),self.wire+'# TYPE surprise_total counter\n')
    def test_wrong_type_rejected(self):
        old='# TYPE assistant_llm_requests_total counter';new=old.replace('counter','gauge')
        self.reject(lambda x:x['observations'][0]['body'].update(families=[line.replace(old,new) for line in x['observations'][0]['body']['families']]),self.wire.replace(old,new))
    def test_missing_family_rejected(self):self.reject(lambda x:x['observations'][0]['body']['families'].pop())
    def test_wire_and_trace_schema_must_agree(self):
        self.reject(lambda x:None,self.wire.replace('# TYPE assistant_llm_requests_total counter','# TYPE assistant_llm_requests_total gauge'))
    def test_duplicate_help_rejected(self):
        wire='# HELP jvm_gc_collections_total fixture\n# HELP jvm_gc_collections_total fixture\n'+self.wire
        self.reject(lambda x:None,wire)
    def test_malformed_metadata_rejected(self):
        self.reject(lambda x:None,'# HELP\n'+self.wire)
    def test_duplicate_series_rejected(self):
        line=next(line for line in self.wire.splitlines() if line.startswith('jvm_gc_collections_total{'))
        self.reject(lambda x:None,self.wire.replace(line,line+'\n'+line,1))
    def test_metadata_after_sample_rejected(self):
        typ='# TYPE jvm_gc_collections_total counter';sample=next(line for line in self.wire.splitlines() if line.startswith('jvm_gc_collections_total{'))
        self.reject(lambda x:None,self.wire.replace(typ+'\n'+sample,sample+'\n'+typ,1))
    def test_histogram_bucket_order_is_not_sorted(self):
        lines=self.wire.splitlines();indices=[i for i,line in enumerate(lines) if line.startswith('assistant_chat_retrieval_duration_seconds_bucket')]
        lines[indices[0]],lines[indices[1]]=lines[indices[1]],lines[indices[0]]
        self.reject(lambda x:None,'\n'.join(lines)+'\n')
    def test_metrics_status_still_exact(self):self.reject(lambda x:x['observations'][0].update(status=500))
    def test_case_cannot_be_reused_for_other_route(self):self.reject(lambda x:x['observations'][0].update(route_id='GET /other'))
    def test_score_change_rejected(self):self.reject(lambda x:x['observations'][1]['body'].update(score=99))
    def test_role_change_rejected(self):self.reject(lambda x:x['observations'][1]['body'].update(role='admin'))
    def test_business_array_reverse_rejected(self):self.reject(lambda x:x['observations'][1]['body']['rows'].reverse())
    def test_duplicate_business_identity_rejected(self):self.reject(lambda x:x['observations'][1]['body']['rows'].__setitem__(1,copy.deepcopy(x['observations'][1]['body']['rows'][0])))
    def test_same_kind_cross_case_identity_drift_rejected(self):self.reject(lambda x:x['observations'][1]['body'].update(task_id='f'*32))

if __name__=='__main__':unittest.main()
