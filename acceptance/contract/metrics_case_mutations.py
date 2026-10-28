"""真实完整候选针对单metrics政策的正例/反例；不启动Java/PG、不改原产物。"""
import argparse
import copy
import json
from pathlib import Path
from acceptance.contract.metrics_case import CASE,compare_with_policy,wire_groups
from acceptance.contract.mutations import run
from migration.oracle.compare import Difference,InvalidTrace
from migration.oracle.verify import load_manifest,load_json
from migration.oracle.verify_v2_regressions import mutate_business

def validate(expected,candidate,wire):
    rows=run(candidate,comparison=lambda _left,right:compare_with_policy(expected,right,wire))
    rows[0]['case_id']='full-golden-single-metrics-policy-control'
    def metric(trace):return next(o for o in trace['observations'] if o['case_id']==CASE)
    def reject(name,change=lambda trace:None,changed_wire=None):
        altered=copy.deepcopy(candidate);change(altered)
        try:compare_with_policy(expected,altered,wire if changed_wire is None else changed_wire)
        except (Difference,InvalidTrace,ValueError) as error:
            rows.append({'case_id':name,'expected':'reject','actual':'reject','detail':str(error)})
        else:rows.append({'case_id':name,'expected':'reject','actual':'FALSE_PASS'})
    reject('role-consistent-business-chain-change',lambda trace:mutate_business(trace,'users','roles',lambda _: ['admin']))
    reject('metrics-duplicate-descriptor-same-type',lambda trace:metric(trace)['body']['families'].append(metric(trace)['body']['families'][0]))
    old='# TYPE assistant_llm_requests_total counter';new=old.replace('counter','gauge')
    reject('metrics-wrong-type',lambda trace:metric(trace)['body'].update(families=[s.replace(old,new) for s in metric(trace)['body']['families']]),wire.replace(old,new))
    unknown='# TYPE unexpected_total counter'
    reject('metrics-unknown-family',lambda trace:metric(trace)['body']['families'].append(unknown),wire+unknown+'\n')
    reject('metrics-missing-family',lambda trace:metric(trace)['body']['families'].pop())
    reject('metrics-wire-trace-disagreement',changed_wire=wire.replace(old,new))
    reject('metrics-status-500',lambda trace:metric(trace).update(status=500))
    lines=wire.splitlines();indices=[i for i,line in enumerate(lines) if '_bucket{' in line]
    assert len(indices)>1,'actual checkpoint must include real HTTP histogram buckets'
    lines[indices[0]],lines[indices[1]]=lines[indices[1]],lines[indices[0]]
    reject('metrics-histogram-bucket-reverse',changed_wire='\n'.join(lines)+'\n')
    reject('metrics-duplicate-wire-type',changed_wire=wire+old+'\n')
    reject('metrics-malformed-metadata',changed_wire='# HELP\n'+wire)
    _,groups=wire_groups(wire)
    reordered='\n'.join(line for name in reversed(list(groups)) for line in groups[name])+'\n'
    altered=copy.deepcopy(candidate);metric(altered)['body']['families'].reverse()
    compare_with_policy(expected,altered,reordered)
    rows.append({'case_id':'only-independent-metric-group-and-descriptor-order','expected':'pass','actual':'pass'})
    return rows

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',required=True,type=Path);parser.add_argument('--wire',required=True,type=Path);parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();root=Path(__file__).resolve().parents[2]
    load_manifest(root/'migration/fixtures/p0-v2','p0-v2')
    rows=validate(load_json(root/'migration/fixtures/p0-v2/python-golden.json'),load_json(args.candidate),args.wire.read_text())
    payload={'scope':'full frozen golden versus actual candidate through single metrics policy; original raw CLI verdict unchanged',
        'total':len(rows),'false_pass':sum(row['actual']=='FALSE_PASS' for row in rows),'cases':rows}
    args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:value for key,value in payload.items() if key!='cases'},ensure_ascii=False))
    return int(payload['false_pass']!=0)

if __name__=='__main__':raise SystemExit(main())
