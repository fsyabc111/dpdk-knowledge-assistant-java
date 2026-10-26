"""只读差分归因索引；不改变oracle判定、不过滤失败、不写黄金。"""
import argparse
import json
from pathlib import Path
from migration.oracle.compare import normalize

def differences(g,a,path='$'):
    if type(g)!=type(a):return [{'path':path,'kind':'type'}]
    if isinstance(g,dict):
        out=[{'path':path,'kind':'keys','expected_only':sorted(g.keys()-a.keys()),'actual_only':sorted(a.keys()-g.keys())}] if g.keys()!=a.keys() else []
        for k in sorted(g.keys()&a.keys()):out+=differences(g[k],a[k],path+'.'+k)
        return out
    if isinstance(g,list):
        out=[{'path':path,'kind':'length','expected':len(g),'actual':len(a)}] if len(g)!=len(a) else []
        for i,(e,v) in enumerate(zip(g,a)):out+=differences(e,v,path+'['+str(i)+']')
        return out
    if g==a:return []
    return [{'path':path,'kind':'value','identity_alias_only':isinstance(g,str) and isinstance(a,str) and g.startswith('<identity:') and a.startswith('<identity:')}]

def main():
    p=argparse.ArgumentParser();p.add_argument('--golden',required=True,type=Path);p.add_argument('--candidate',required=True,type=Path);p.add_argument('--out',required=True,type=Path);args=p.parse_args()
    g=normalize(json.loads(args.golden.read_text()),allow_placeholders=True);a=normalize(json.loads(args.candidate.read_text()))
    rows=[]
    for e,v in zip(g['observations'],a['observations']):
        diff=differences(e,v)
        if diff:rows.append({'case_id':e['case_id'],'paths':diff})
    payload={'purpose':'全部路径索引；不豁免任何差分。identity_alias_only可能受前序新增/缺少实体影响，须结合原始跨表关联归因，不能单凭编号变化判业务bug。','cases':rows,'top_level_non_observation':differences({k:v for k,v in g.items() if k!='observations'},{k:v for k,v in a.items() if k!='observations'})}
    args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'cases_with_difference':len(rows),'paths':sum(len(r['paths']) for r in rows)}))
if __name__=='__main__':main()
