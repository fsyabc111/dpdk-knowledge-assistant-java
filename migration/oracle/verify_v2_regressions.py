"""已采集p0-v2的真实CLI破坏回归；仅读冻结黄金，候选副本留artifacts。"""
from __future__ import annotations
import copy
import json
from pathlib import Path
import subprocess
import sys
from .safety import ROOT
from .compare import compare


def replace(value,old,new):
    if isinstance(value,dict):return {k:replace(v,old,new) for k,v in value.items()}
    if isinstance(value,list):return [replace(v,old,new) for v in value]
    return new if value==old else value


def mutate_business(value,table,column,change,selector=lambda row:True):
    if isinstance(value,dict):
        tables=value.get('tables')
        if isinstance(tables,dict) and table in tables:
            section=tables[table]
            groups=[section] if isinstance(section,list) else [section.get('removed',[]),section.get('added',[])]
            for rows in groups:
                for row in rows:
                    if selector(row):row[column]=change(row[column])
        for key,item in value.items():
            if key!='tables':mutate_business(item,table,column,change,selector)
        # tables内已处理目标，其他表无须递归，避免单行被重复破坏。
    elif isinstance(value,list):
        for item in value:mutate_business(item,table,column,change,selector)


def main():
    raw=ROOT/'.migration-artifacts/p0/candidate-trace-p0-v2.json';base=json.loads(raw.read_text())
    folder=ROOT/'.migration-artifacts/p0/v2-cli-regressions';folder.mkdir(parents=True,exist_ok=True)
    results=[]
    def run_case(name,value,expected):
        path=folder/(name+'.json');path.write_text(json.dumps(value,ensure_ascii=False))
        cmd=[sys.executable,'-m','migration.oracle.verify','--fixture','p0-v2','--candidate',str(path)]
        result=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True)
        assert result.returncode==expected,(name,result.returncode,result.stdout,result.stderr)
        assert not result.stderr,(name,result.stderr)
        output=json.loads(result.stdout)
        results.append({'case_id':name,'status':'通过','expected_exit':expected,'actual_exit':result.returncode,'output':output})
    run_case('unchanged',base,0)
    variants=[('quota','usage_counters','chat_count',lambda x:x+1,lambda r:r['kind']=='chat'),
      ('grade-score','grading_submissions','score',lambda x:x+1,lambda r:True),
      ('job-submission-flag','rag_task_jobs','submission_done',lambda x:0,lambda r:x_present(r,'submission_done',1)),
      ('permission-role','users','roles',lambda x:['admin'],lambda r:r['user_id']=='U20270000011'),
      ('permission-tree','users','created_by',lambda x:'U20270000003',lambda r:r['user_id']=='U20270000011'),
      ('enabled','users','enabled',lambda x:False,lambda r:r['user_id']=='U20270000011'),
      ('fee-null-zero','upstream_usage_records','cost_micros',lambda x:0,lambda r:r['cost_micros'] is None),
      ('fee-type','upstream_usage_records','cost_micros',lambda x:'0',lambda r:r['cost_micros'] is None),
      ('citation-rank','assistant_citations','rank',lambda x:x+1,lambda r:True),
      ('citation-round4-exact','assistant_citations','score',lambda x:x+1e-13,lambda r:True),
      ('knowledge-revision','rag_knowledge','revision',lambda x:x+1,lambda r:True)]
    for name,table,column,change,selector in variants:
        value=copy.deepcopy(base);mutate_business(value,table,column,change,selector)
        assert value['observations'][0]['status']==base['observations'][0]['status']
        assert value['state']['counts']==base['state']['counts']
        run_case(name,value,1)
    value=replace(base,'p0-dedup-job','p0-other-job');run_case('job-entire-link-replacement',value,1)
    digest=next(row['code_hash'] for row in base['state']['business']['tables']['grading_submissions'] if row['assignment_id'] is not None)
    run_case('source-all-hash-replacement',replace(base,digest,'2'*64),1)
    tid=next(o['body']['task_id'] for o in base['observations'] if o['route_id']=='POST /api/chat' and o['kind']=='success')
    run_case('task-all-cross-type-replacement',replace(base,tid,'sess_'+'a'*12),1)
    for key in ['submission_count','student_count']:
        value=copy.deepcopy(base)
        row=next(o['body']['assignments'][0] for o in value['observations'] if o['case_id']=='GET /api/assignments::success-nonempty-manager')
        assert len(row)==12,len(row);row.pop(key);run_case('manager-missing-'+key,value,1)
    score_case=next(i for i,c in enumerate(base['transaction_cases']) if c['case_id']=='pg-vector-rrf-mmr')
    for name,offset,expected in [('raw-score-within',0.5e-12,0),('raw-score-outside',2e-12,1)]:
        value=copy.deepcopy(base);value['transaction_cases'][score_case]['ranked'][0]['score']+=offset;run_case(name,value,expected)
    value=copy.deepcopy(base);value['transaction_cases'][score_case]['ranked'].reverse();run_case('raw-rank-order',value,1)
    value=copy.deepcopy(base);unknown=next(r for r in value['upstream_vectors'] if r.get('usage_source')=='unknown');assert unknown['total_tokens'] is None;unknown['total_tokens']=0;run_case('usage-unknown-zero',value,1)
    value=copy.deepcopy(base);value['semantic_vectors']['redaction'][0]['actual']='grade_abcdefab12345678';run_case('redaction-numeric-vector',value,1)
    for name,mutate in [('state-array',lambda x:x.update(state=[])),('transactions-null',lambda x:x.update(transaction_cases=None)),
       ('request-array',lambda x:x['observations'][0].update(request=[])),('request-input-array',lambda x:x['observations'][0]['request'].update(input=[])),
       ('request-params-array',lambda x:x['observations'][0]['request'].update(params=[])),('auth-step-array',lambda x:x.update(auth_state_step=[]))]:
        value=copy.deepcopy(base);mutate(value);run_case(name,value,2)
    run_case('top-array',[],2)
    value=copy.deepcopy(base);value['observations'][0]['body']['expires_at']='2026-99-24T04:00:00+00:00';run_case('invalid-calendar',value,2)
    value=copy.deepcopy(base);value['malicious']=float('nan');run_case('nonfinite-json',value,2)
    value=copy.deepcopy(base);nested=0
    for _ in range(70):nested={'nested':nested}
    value['malicious']=nested;run_case('depth-limit',value,2)
    value=copy.deepcopy(base);event_record=next(o for o in value['observations'] if o['media_type']=='text/event-stream');event_record['body']=[{'event':'error','data':[]}];run_case('sse-data-container',value,2)
    for label,bad_status in [('object',{}),('array',[]),('null',None),('bool',True),('integer',7),('float',1.5)]:
        value=copy.deepcopy(base)
        stream=next(o for o in value['observations'] if o['media_type']=='text/event-stream')
        done=next(event for event in stream['body'] if event['event']=='done')
        done['data']['answer_status']=bad_status
        run_case('sse-done-status-'+label,value,2)
    deep=folder/'json-parser-depth.json';deep.write_text('{"observations":[],"attack":'+'['*2000+'0'+']'*2000+'}')
    result=subprocess.run([sys.executable,'-m','migration.oracle.verify','--fixture','p0-v2','--candidate',str(deep)],cwd=ROOT,capture_output=True,text=True)
    assert result.returncode==2 and not result.stderr
    results.append({'case_id':'json-parser-depth','status':'通过','expected_exit':2,'actual_exit':result.returncode,'output':json.loads(result.stdout)})
    # manifest由verify逐项SHA校验；freeze无候选参数且已有版本拒绝覆盖。
    frozen=subprocess.run([sys.executable,'-m','migration.oracle.freeze','--fixture','p0-v2'],cwd=ROOT,capture_output=True,text=True)
    assert frozen.returncode==1
    report={'fixture_version':'p0-v2','cases':results,'frozen_overwrite_exit':frozen.returncode,'comparison':'all mutated business cases retain HTTP status and total row counts; explicit actual business values are compared'}
    target=ROOT/'docs/java-migration/evidence/J0-02/auto/p0-v2-cli-regressions.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'cases':len(results),'passed':len(results),'freeze_rejected':True}))


def x_present(row,key,value):return row.get(key)==value

if __name__=='__main__':main()
