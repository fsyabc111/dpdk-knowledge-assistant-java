"""真实Java候选自身为正例的严格判定器反例；不代表候选与Python黄金等价。"""
import argparse
import copy
import json
from pathlib import Path
from migration.oracle.compare import compare,Difference,InvalidTrace
from migration.oracle.verify_v2_regressions import mutate_business


def run(candidate):
    rows=[]
    compare(candidate,copy.deepcopy(candidate))
    rows.append({'case_id':'java-candidate-self-control','expected':'pass','actual':'pass'})
    def mutation(name,change):
        altered=copy.deepcopy(candidate);change(altered)
        try:compare(candidate,altered)
        except (Difference,InvalidTrace) as e:rows.append({'case_id':name,'expected':'reject','actual':'reject','detail':str(e)})
        else:rows.append({'case_id':name,'expected':'reject','actual':'FALSE_PASS'})
    def obs(x,cid):return next(o for o in x['observations'] if o['case_id']==cid)
    mutation('http-200-to-500-same-body',lambda x:obs(x,'POST /api/chat::success').update(status=500))
    mutation('grade-80-to-99-business-chain-consistent',lambda x:mutate_business(x,'grading_submissions','score',lambda _:99))
    mutation('array-reverse',lambda x:obs(x,'GET /api/admin/users::success')['body']['users'].reverse())
    mutation('duplicate-identity-same-array-size',lambda x:obs(x,'GET /api/admin/users::success')['body']['users'].__setitem__(1,copy.deepcopy(obs(x,'GET /api/admin/users::success')['body']['users'][0])))
    mutation('same-kind-task-cross-case-link-drift',lambda x:obs(x,'POST /api/chat::success')['body'].update(task_id='f'*32))
    mutation('task-to-session-type-consistent-string',lambda x:obs(x,'POST /api/chat::success')['body'].update(task_id='sess_'+'f'*12))
    mutation('unknown-fee-null-to-zero-consistent-chain',lambda x:mutate_business(x,'upstream_usage_records','cost_micros',lambda _:0))
    mutation('student-tree-consistent-chain-wrong-owner',lambda x:mutate_business(x,'users','created_by',lambda _:'U20270000003',lambda r:r['user_id']=='U20270000011'))
    mutation('missing-response-field',lambda x:obs(x,'POST /api/chat::success')['body'].pop('confidence'))
    mutation('business-delta-broken-final',lambda x:x['state']['business']['tables']['users'].pop())
    def sse_drift(x):
        o=next(o for o in x['observations'] if o['media_type']=='text/event-stream');o['body'][0]['data']['task_id']='f'*32
    mutation('sse-start-task-identity-drift',sse_drift)
    return rows

def main():
    p=argparse.ArgumentParser();p.add_argument('--candidate',required=True,type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
    candidate=json.loads(a.candidate.read_text());rows=run(candidate);a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(rows,ensure_ascii=False,indent=2));print(json.dumps({'total':len(rows),'false_pass':sum(r['actual']=='FALSE_PASS' for r in rows),'scope':'comparator real Java candidate self-control, not golden equivalence'}));return 1 if any(r['actual']=='FALSE_PASS' for r in rows) else 0
if __name__=='__main__':raise SystemExit(main())
