"""两轴审查修复的纯内存/CLI回归；不导入app、不连接PG。"""
from __future__ import annotations
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from .business_state import step
from .compare import compare,Difference,InvalidTrace,parse_sse,validate_sse,normalize
from . import verify
from .safety import ROOT


def state():
    return {'projection_version':'business-v2','tables':{
      'users':[{'user_id':'U20270000011','roles':['viewer'],'created_by':'U20270000002','enabled':True}],
      'usage_counters':[{'subject':'U20270000011','day':'2026-10-24','kind':'chat','chat_count':1}],
      'grading_submissions':[{'submission_id':'grade_'+'c'*16,'score':80,'job_id':'p0-job','code_hash':'1'*64}],
      'rag_task_jobs':[{'ledger_id':'d'*32,'task_id':'a'*32,'job_id':'p0-job','submission_done':0,'code_hash':'1'*64,'job_result':{'score':80,'ok':True}}],
      'upstream_usage_records':[{'usage_id':'use_'+'e'*12,'usage_source':'unknown','total_tokens':None,'cost_micros':None,'duration_ms':3}]}}


def trace():
    before=state();after=copy.deepcopy(before)
    after['tables']['usage_counters'][0]['chat_count']=2
    after['tables']['rag_task_jobs'][0]['submission_done']=1
    return {'version':'p0-v2','fixture_version':'p0-v2','business_initial':before,
      'observations':[{'case_id':'synthetic','route_id':'POST /api/chat','status':200,'media_type':'application/json',
        'request':{'input':{'request_id':'11111111-1111-4111-8111-111111111111'}},
        'body':{'task_id':'a'*32,'session_id':'sess_'+'b'*12,'message_id':'f'*32,
                'request_id':'11111111-1111-4111-8111-111111111111',
                'created_at':'2026-10-24T04:00:00+00:00','expires_at':'2026-10-25T04:00:00+00:00'},
        **step(before,after)}],
      'auth_state_step':{'case_id':'auth',**step(after,after)},'transaction_cases':[],
      'state':{'business':after}}


def replace(value,old,new):
    if isinstance(value,dict):return {k:replace(v,old,new) for k,v in value.items()}
    if isinstance(value,list):return [replace(v,old,new) for v in value]
    return new if value==old else value


class ReviewFixTests(unittest.TestCase):
    def test_business_values_with_same_http_and_counts(self):
        base=trace();compare(base,copy.deepcopy(base))
        variants=[('quota','usage_counters','chat_count',3),('score','grading_submissions','score',90),
          ('job-flag','rag_task_jobs','submission_done',0),('job-link','grading_submissions','job_id','p0-other-job'),
          ('roles','users','roles',['admin']),('tree','users','created_by','U20270000003'),
          ('enabled','users','enabled',False),('unknown-zero','upstream_usage_records','total_tokens',0),
          ('fee-type','upstream_usage_records','cost_micros','0')]
        for name,table,column,value in variants:
            with self.subTest(name=name):
                altered=copy.deepcopy(base)
                # 一致重建初态/前后delta/终态，HTTP与行数不变，仅业务列错写。
                def walk(v):
                    if isinstance(v,dict):
                        if 'tables' in v and table in v['tables'] and isinstance(v['tables'][table],list):v['tables'][table][0][column]=value
                        for item in list(v.values()):walk(item)
                    elif isinstance(v,list):
                        for item in v:walk(item)
                walk(altered)
                altered['observations'][0].update(step(altered['business_initial'],altered['state']['business']))
                altered['auth_state_step'].update(step(altered['state']['business'],altered['state']['business']))
                self.assertEqual(base['observations'][0]['body'],altered['observations'][0]['body'])
                self.assertEqual(base['observations'][0]['business_after'],altered['observations'][0]['business_after'])
                with self.assertRaises(Difference):compare(base,altered)
    def test_cross_entity_all_references_rejected(self):
        base=trace();wrong=replace(base,'a'*32,'sess_'+'a'*12)
        with self.assertRaises(Difference):compare(base,wrong)
        for field,bad in [('session_id','a'*32),('message_id','asg_'+'a'*16)]:
            altered=copy.deepcopy(base);altered['observations'][0]['body'][field]=bad
            with self.assertRaises(Difference):compare(base,altered)
        wrong_grade=replace(base,'grade_'+'c'*16,'asg_'+'c'*16)
        with self.assertRaises(Difference):compare(base,wrong_grade)
    def test_consistent_source_hash_replacement_rejected(self):
        base=trace();candidate=replace(base,'1'*64,'2'*64)
        with self.assertRaises(Difference):compare(base,candidate)
    def test_sse_error_endings_and_wire(self):
        error={'event':'error','data':{'message':'服务暂时不可用，请稍后重试','retryable':True}}
        for events in [[error],[{'event':'start','data':{'task_id':'a'*32}},error]]:
            sample={'observations':[{'status':200,'media_type':'text/event-stream','body':events}]}
            compare(sample,copy.deepcopy(sample));self.assertEqual(validate_sse(events),'error')
        wire='event: error\ndata: '+json.dumps(error['data'],ensure_ascii=False)+'\n\n'
        self.assertEqual(parse_sse([wire]),[error])
        with self.assertRaises(Difference):parse_sse([wire.replace('\n','\r\n')])
        with self.assertRaises(Difference):validate_sse([error,{'event':'delta','data':{'delta':'late'}}])
        done='event: done\ndata: {"answer_status":"answered"}\n\n'
        with self.assertRaises(Difference):validate_sse(parse_sse([done[:-2]]))
        with self.assertRaises(Difference):validate_sse([{'event':'done','data':{'status':'invented','terminal':True}}])
        raw=wire.encode();self.assertEqual(parse_sse([raw[i:i+1] for i in range(len(raw))]),[error])
    def test_nonempty_manager_shape_missing_field_rejected(self):
        manager={'observations':[{'status':200,'media_type':'application/json','route_id':'GET /api/assignments',
          'body':{'assignments':[{'id':'asg_'+'a'*16,'title':'合成作业','submission_count':1,'student_count':3}]}}]}
        for key in ['submission_count','student_count']:
            candidate=copy.deepcopy(manager);candidate['observations'][0]['body']['assignments'][0].pop(key)
            with self.assertRaises(Difference):compare(manager,candidate)
    def test_cli_schema_business_and_limits(self):
        original=trace();golden=normalize(original)
        folder_parent=ROOT/'.migration-artifacts/p0/review-fix';folder_parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder_parent) as tmp:
            fake_root=Path(tmp);folder=fake_root/'migration/fixtures/p0-v2';folder.mkdir(parents=True)
            path=folder/'python-golden.json';path.write_text(json.dumps(golden))
            (folder/'manifest.json').write_text(json.dumps({'fixture_id':'p0-v2','files':{'python-golden.json':hashlib.sha256(path.read_bytes()).hexdigest()}}))
            target=fake_root/'candidate.json'
            variants=[('pass',original,0),('top-array',[],2),('null',None,2),
              ('observations-object',{'observations':{}},2)]
            bad_date=copy.deepcopy(original);bad_date['observations'][0]['body']['expires_at']='2026-99-24T04:00:00+00:00'
            variants.append(('calendar',bad_date,2))
            for label,bad_status in [('object',{}),('array',[]),('null',None),('bool',True),('integer',7),('float',1.5)]:
                broken=copy.deepcopy(original)
                broken['observations'][0].update(media_type='text/event-stream',body=[{'event':'done','data':{'answer_status':bad_status,'terminal':True}}])
                variants.append(('sse-done-status-'+label,broken,2))
            for name,mutate in [
                ('state-array',lambda x:x.update(state=[])),
                ('transaction-null',lambda x:x.update(transaction_cases=None)),
                ('request-array',lambda x:x['observations'][0].update(request=[])),
                ('request-input-array',lambda x:x['observations'][0]['request'].update(input=[])),
                ('request-params-array',lambda x:x['observations'][0]['request'].update(params=[])),
                ('transaction-item-null',lambda x:x.update(transaction_cases=[None])),
                ('auth-step-array',lambda x:x.update(auth_state_step=[]))]:
                broken=copy.deepcopy(original);mutate(broken);variants.append((name,broken,2))
            business=copy.deepcopy(original);business['observations'][0]['body']['request_id']='22222222-2222-4222-8222-222222222222'
            variants.append(('business',business,1))
            for name,value,expected_code in variants:
                target.write_text(json.dumps(value));out=io.StringIO()
                with patch.object(verify,'ROOT',fake_root),patch.object(sys,'argv',['verify','--fixture','p0-v2','--candidate',str(target)]),contextlib.redirect_stdout(out):code=verify.main()
                self.assertEqual(code,expected_code,name);self.assertIsInstance(json.loads(out.getvalue()),dict)
                cli=[sys.executable,'-c',"from pathlib import Path; from migration.oracle import verify; verify.ROOT=Path("+repr(str(fake_root))+"); raise SystemExit(verify.main())",'--fixture','p0-v2','--candidate',str(target)]
                completed=subprocess.run(cli,capture_output=True,text=True)
                self.assertEqual(completed.returncode,expected_code,name);self.assertEqual(completed.stderr,'',name);json.loads(completed.stdout)
            target.write_text('{"observations":[],"attack":'+'['*2000+'0'+']'*2000+'}')
            command=[sys.executable,'-c',"from pathlib import Path; from migration.oracle import verify; verify.ROOT=Path("+repr(str(fake_root))+"); raise SystemExit(verify.main())",'--fixture','p0-v2','--candidate',str(target)]
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(result.returncode,2);self.assertEqual(result.stderr,'');self.assertEqual(json.loads(result.stdout)['status'],'invalid_manifest_or_input')
    def test_raw_retrieval_tolerance_only(self):
        from .compare import exact
        base={'transaction_cases':[{'ranked':[{'chunk_id':'li_a','score':0.75},{'chunk_id':'li_b','score':0.5}]}]}
        within=copy.deepcopy(base);within['transaction_cases'][0]['ranked'][0]['score']+=0.5e-12;exact(base,within)
        beyond=copy.deepcopy(base);beyond['transaction_cases'][0]['ranked'][0]['score']+=2e-12
        with self.assertRaises(Difference):exact(base,beyond)
        reordered=copy.deepcopy(base);reordered['transaction_cases'][0]['ranked'].reverse()
        with self.assertRaises(Difference):exact(base,reordered)
        for value in [('citation',{'score':1.02},{'score':1.0200000000001}),('business',{'score':80.0},{'score':80.0000000000001})]:
            with self.assertRaises(Difference):exact(value[1],value[2],f'$.{value[0]}')
    def test_text_projection_known_entities_only(self):
        from .business_state import text_identity_projection
        first='grade_'+'a'*16;second='grade_'+'b'*16;unknown='grade_'+'c'*16
        known={first:'submission_id',second:'submission_id'}
        fingerprint,links=text_identity_projection('正文 '+first,known)
        changed,changed_links=text_identity_projection('正文 '+second,known)
        self.assertEqual(fingerprint,changed);self.assertNotEqual(links,changed_links)
        literal,_=text_identity_projection('修改正文 '+first,known);self.assertNotEqual(fingerprint,literal)
        untouched,refs=text_identity_projection('字面示例 '+unknown,known)
        self.assertEqual(refs,[]);self.assertEqual(untouched,hashlib.sha256(('字面示例 '+unknown).encode()).hexdigest())
    def test_projection_privacy_columns(self):
        from .business_state import QUERIES,project_row
        self.assertTrue(all('SELECT *' not in query.upper() for query in QUERIES.values()))
        self.assertNotIn('password_hash',QUERIES['users'])
        self.assertNotIn('token_hash',QUERIES['api_tokens'])
        self.assertNotIn('source_text,',QUERIES['rag_task_inputs'])
        row=project_row('assistant_messages',{'metadata':{'task_id':'a'*32,'prompt':'DO NOT EXPORT','raw_model_output':'DO NOT EXPORT'}})
        self.assertEqual(row['metadata'],{'task_id':'a'*32})
        self.assertNotIn('raw_output',project_row('rag_task_jobs',{'job_result':{'score':80,'raw_output':'DO NOT EXPORT'}})['job_result'])


if __name__=='__main__':unittest.main()
