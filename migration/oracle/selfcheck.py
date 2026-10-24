"""比较器正反例：运行 python -m migration.oracle.selfcheck。"""
from __future__ import annotations
import copy
import json
from pathlib import Path
from .compare import compare, Difference, parse_sse, validate_sse
from .safety import ROOT, validate_url


def run():
    base={'observations':[{'status':200,'request':{'input':{'request_id':'11111111-1111-4111-8111-111111111111'}},'media_type':'application/json','body':{
        'session_id':'sess_aaaaaaaaaaaa','task_id':'a'*32,'message_id':'b'*32,
        'answer':'答案','nullable':None,'ordered':[1,2],'permission':'owned','request_id':'11111111-1111-4111-8111-111111111111',
        'created_at':'2026-10-24T04:00:00+00:00','updated_at':'2026-10-24T04:00:01+00:00','expires_at':'2026-10-25T04:00:00+00:00'}},
        {'status':200,'media_type':'text/event-stream','body':[
            {'event':'start','data':{'session_id':'sess_aaaaaaaaaaaa','task_id':'a'*32}},
            {'event':'sources','data':{'citations':[{'chunk_id':'li_a'}]}},
            {'event':'sources','data':{'citations':[{'chunk_id':'li_a'},{'chunk_id':'li_b'}]}},
            {'event':'delta','data':{'delta':'答案','message_id':'b'*32}},
            {'event':'done','data':{'terminal':True,'answer_status':'answered','task_id':'a'*32}}]}],
        'state':{'task_id':'a'*32,'message_id':'b'*32,'quota_count':1,'grade_count':1}}
    compare(base,copy.deepcopy(base))
    def reordered(value):
        if isinstance(value,dict):return {k:reordered(value[k]) for k in reversed(list(value))}
        if isinstance(value,list):return [reordered(v) for v in value]
        return value
    compare(base,reordered(base))
    mutations={
        'expired':lambda x:x['observations'][0]['body'].update(expires_at='2026-10-23T04:00:00+00:00'),
        'created_after_updated':lambda x:x['observations'][0]['body'].update(created_at='2026-10-24T05:00:00+00:00'),
        'timezone_changed':lambda x:x['observations'][0]['body'].update(created_at='2026-10-24T12:00:00+08:00'),
        'time_naive':lambda x:x['observations'][0]['body'].update(created_at='2026-10-24T04:00:00'),
        'time_precision':lambda x:x['observations'][0]['body'].update(created_at='2026-10-24T04:00:00.1+00:00'),
        'caller_request_identity':lambda x:x['observations'][0]['body'].update(request_id='22222222-2222-4222-8222-222222222222'),
        'task_identity_format':lambda x:x['observations'][0]['body'].update(task_id='invalid-task-id'),
        'http_status':lambda x:x['observations'][0].update(status=201),
        'field_type':lambda x:x['state'].update(quota_count=True),
        'null_missing':lambda x:x['observations'][0]['body'].pop('nullable'),
        'array_order':lambda x:x['observations'][0]['body']['ordered'].reverse(),
        'permission':lambda x:x['observations'][0]['body'].update(permission='foreign'),
        'side_effect':lambda x:x['state'].update(grade_count=2),
        'identity_link':lambda x:x['state'].update(task_id='task-other'),
        'sse_sources_prefix':lambda x:x['observations'][1]['body'][2]['data']['citations'].reverse(),
        'sse_pending_terminal':lambda x:x['observations'][1]['body'][-1]['data'].update(answer_status='pending'),
        'sse_missing_done':lambda x:x['observations'][1]['body'].pop(),
        'sse_post_done':lambda x:x['observations'][1]['body'].append({'event':'delta','data':{'delta':'late'}}),
    }
    results=[]
    for name,mutate in mutations.items():
        altered=copy.deepcopy(base); mutate(altered)
        try: compare(base,altered)
        except Difference: results.append({'case_id':name,'status':'通过','assertion':'差分拒绝注入错误'})
        else: raise AssertionError(f'比较器未发现 {name}')
    wire=''.join(f"event: {e['event']}\ndata: {json.dumps(e['data'],ensure_ascii=False)}\n\n" for e in base['observations'][1]['body']).encode()
    exact=parse_sse([wire]); fragmented=parse_sse([wire[i:i+1] for i in range(len(wire))])
    assert exact==fragmented; validate_sse(fragmented)
    try: validate_sse(parse_sse([wire[:-2]]))
    except Difference: pass
    else: raise AssertionError('截断done帧在EOF被误当终态')
    results.append({'case_id':'sse_done_without_delimiter','status':'通过','assertion':'done JSON闭括号后、空行终止前截断不能成为完整终态'})
    results.append({'case_id':'sse_byte_fragmentation','status':'通过','assertion':'UTF8逐字节分块语义相同'})
    dangerous=['postgresql:///assistant','postgresql://p0_oracle@localhost:55473/p0_python_oracle',
      'postgresql://p0_oracle@127.0.0.1:5432/p0_python_oracle',
      'postgresql://postgres@127.0.0.1:55473/p0_python_oracle',
      'postgresql://p0_oracle@127.0.0.1:55473/assistant',
      'postgresql://p0_oracle@192.0.2.1:55473/p0_python_oracle',
      'postgresql://p0_oracle@127.0.0.1:55473/p0_python_oracle?host=/tmp']
    for url in dangerous:
        try: validate_url(url)
        except RuntimeError: pass
        else: raise AssertionError('危险目标未拒绝')
    results.append({'case_id':'dangerous_targets','status':'通过','assertion':f'{len(dangerous)}个危险URL在连接前拒绝'})
    out=ROOT/'docs/java-migration/evidence/J0-02/auto/comparator-selfcheck.json'
    out.write_text(json.dumps({'version':'p0-v1','python_self_comparison':'通过','cases':results},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'positive':3,'negative':len(mutations)+1,'dangerous_targets':len(dangerous)}))

if __name__=='__main__':run()
