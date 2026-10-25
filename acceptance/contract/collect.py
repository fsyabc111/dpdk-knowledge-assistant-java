"""真实Java HTTP候选采集器；输入取冻结case，输出仅来自HTTP/独立PG/替身账本。

不删字段、不放宽比较、不生成黄金。完整性缺项明确记为blocked并使CLI非0；
该阶段暂未提供的事务/auth/upstream Java helper不能填Python输出冒充采集。
"""
from __future__ import annotations
import argparse
from collections import Counter
import copy
from datetime import date,datetime
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,urlopen
from acceptance.contract.isolation import PgIsolation,OwnedDatabase
from acceptance.contract.seed import IDS
from migration.oracle.business_state import QUERIES,project_row,step
from migration.oracle.compare import parse_sse,validate_sse,compare,Difference,InvalidTrace

BASELINE='80b29135faddd16d5e39fdbf0633291ea6a20489'
ROOT=Path(__file__).resolve().parents[2]
HEADER_KEYS=['www-authenticate','allow','access-control-allow-origin','access-control-allow-methods','access-control-allow-headers','access-control-allow-credentials','x-content-type-options','x-frame-options','referrer-policy','content-security-policy','x-accel-buffering','cache-control']
PLACEHOLDER=re.compile(r'<identity:\d+>')

def plain(v):return json.loads(json.dumps(v,ensure_ascii=False,default=lambda v:v.isoformat() if isinstance(v,(datetime,date)) else str(v)))
def write(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)

def snapshot(h,db):
    h.verify_owned(db)
    from psycopg.rows import dict_row
    raw={}
    with h._connect(db.name) as conn:
        conn.row_factory=dict_row
        conn.execute('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
        try:
            for table,query in QUERIES.items():raw[table]=[dict(r) for r in conn.execute(query).fetchall()]
        finally:conn.execute('ROLLBACK')
    aliases={'task_id','session_id','message_id','assignment_id','submission_id','knowledge_id','revision_id','citation_id','ledger_id','issue_event_id','assignment_item_id','audit_id','usage_id','reset_request_id'}
    entities={r[k]:k for rows in raw.values() for r in rows for k in aliases if isinstance(r.get(k),str) and r[k]}
    return plain({'projection_version':'business-v2','tables':{t:[project_row(t,r,entities) for r in rows] for t,rows in raw.items()}})

class Collector:
    def __init__(self,h,db,server,upstream,out):
        self.h,self.db,self.server,self.upstream,self.out=h,db,server,upstream,Path(out)
        self.bindings={};self.tokens={};self.observations=[];self.blocked=[];self.last=None
        self.source_marker=hashlib.sha256((BASELINE+':p0-v2-runtime-source').encode()).hexdigest()[:32]
        self.source='/* P0 '+self.source_marker+' */\nint plugin_main(void){return 0;}\n'
        self.latest_reset=None
    def http(self,method,path,*,payload=None,token=None,headers=None,params=None,raw=None,base=None):
        if params:path+='?'+urlencode(params)
        data=raw if raw is not None else json.dumps(payload,ensure_ascii=False).encode() if payload is not None else None
        hdr=dict(headers or {})
        if payload is not None:hdr['Content-Type']='application/json'
        if token:hdr['Authorization']='Bearer '+token
        req=Request((base or self.server['base_url'])+path,data=data,headers=hdr,method=method)
        try:
            with urlopen(req,timeout=40) as r:return r.status,{k.lower():v for k,v in r.headers.items()},r.read()
        except HTTPError as e:return e.code,{k.lower():v for k,v in e.headers.items()},e.read()
    def ledger(self):return json.load(urlopen(self.upstream+'/ledger'))['counts']
    def settle(self):
        result=json.load(urlopen(self.server['control_url']+'/settled',timeout=35))
        if not result['settled']:raise RuntimeError('Java候选任务未收口')
    def bind(self,expected,actual):
        # 只用于把冻结请求参数定位到刚由Java创建的真实资源；不修改实际响应/DB字段。
        if isinstance(expected,str) and PLACEHOLDER.fullmatch(expected) and isinstance(actual,str):
            old=self.bindings.get(expected)
            if old is not None and old!=actual:self.blocked.append({'kind':'routing_identity_drift','expected_alias':expected})
            else:self.bindings[expected]=actual
        elif isinstance(expected,dict) and isinstance(actual,dict):
            for k in expected.keys()&actual.keys():self.bind(expected[k],actual[k])
        elif isinstance(expected,list) and isinstance(actual,list):
            for g,a in zip(expected,actual):self.bind(g,a)
    def bind_initial(self,expected,actual):
        # 初态seed双方同源；按非身份语义键定位，不按随机ID排序绑定。
        keys={'users':('user_id',),'assistant_sessions':('title','user_id'),'assistant_messages':('role','content_fingerprint'),
              'assistant_citations':('chunk_id','rank'),'rag_requests':('question_fingerprint','user_id'),'rag_issues':('status',),
              'rag_releases':('release_id',),'rag_release_nodes':('node_id',),'li_nodes':('node_id',),'knowledge_documents':('document_id',),'knowledge_chunks':('chunk_id',),'rag_index_state':('name',)}
        for t,cols in keys.items():
            for g in expected['tables'][t]:
                matched=[a for a in actual['tables'][t] if all(g.get(k)==a.get(k) for k in cols)]
                if len(matched)==1:self.bind(g,matched[0])
    def resolve(self,value):
        if isinstance(value,str) and PLACEHOLDER.fullmatch(value):
            if value not in self.bindings:self.blocked.append({'kind':'unresolved_request_alias','alias':value});return 'p0-missing'
            return self.bindings[value]
        if isinstance(value,dict):return {k:self.resolve(v) for k,v in value.items()}
        if isinstance(value,list):return [self.resolve(v) for v in value]
        return value
    def request(self,g):
        req=g['request'];actor=req.get('actor','');inp=self.resolve(copy.deepcopy(req.get('input')));params=self.resolve(req.get('params'));pathp=self.resolve(req.get('path_parameters',{}));path=req['path_template']
        for k,v in pathp.items():path=path.replace('{'+k+'}',str(v))
        if isinstance(inp,dict):
            for key in ('password','old_password','new_password','token'):
                if inp.get(key)=='<synthetic-credential>':
                    inp[key]=self.latest_reset if key=='token' else ('p0-synthetic-reset' if key=='new_password' and '/password-reset' in path else 'p0-synthetic-password' if key=='new_password' else IDS['free'] if key=='old_password' else IDS['leader'] if path.endswith('/delete') else inp.get('username',''))
            if inp.get('code')=='<runtime-source>':inp['code']=self.source;inp.pop('code_sha256',None)
        headers={'X-Maintenance-Key':'p0-synthetic-maintenance'} if actor=='maintenance' else {}
        if g['case_id']=='OPTIONS /api/chat::cors-200':headers={'Origin':'http://127.0.0.1:5174','Access-Control-Request-Method':'POST'}
        if g['case_id']=='OPTIONS /api/chat::cors-400':headers={'Origin':'https://p0.invalid','Access-Control-Request-Method':'POST'}
        before=snapshot(self.h,self.db)
        if self.last is not None and self.last!=before:raise RuntimeError('请求间出现未登记业务变化')
        lb=self.ledger();status,hd,raw=self.http(req['method'],path,payload=inp,token=self.tokens.get(actor),headers=headers,params=params)
        self.settle();after=snapshot(self.h,self.db);la=self.ledger()
        media=hd.get('content-type','').split(';')[0]
        if req['method']=='HEAD' or status==204:body=None
        elif media=='text/event-stream':body=parse_sse([raw])
        elif media=='application/json':body=json.loads(raw)
        elif path=='/metrics':body={'families':[x for x in raw.decode().splitlines() if x.startswith('# TYPE ')]}
        else:body={'sha256':hashlib.sha256(raw).hexdigest(),'length':len(raw)}
        self.bind(g.get('body'),body)
        if path=='/api/auth/login' and isinstance(body,dict) and body.get('token') and status==200:
            who=g['kind'].removeprefix('success-');self.tokens[who]=body['token']
        if isinstance(body,dict) and body.get('reset_token'):self.latest_reset=body['reset_token']
        safe=copy.deepcopy(req)
        safe['path_parameters']=pathp;safe['params']=params
        # 输入记录来自实际请求，敏感值按冻结表示脱敏；不把golden的业务输出填进candidate。
        safe['input']=self.safe(inp)
        record={k:g[k] for k in ('case_id','route_id','kind','precondition','upstream_script','after_boundary')}
        record.update(request=safe,status=status,expected_status=g.get('expected_status'),media_type=media,
            headers={k:hd[k] for k in HEADER_KEYS if k in hd},body=body,**step(before,after),
            input_identity_assertions={},side_effect_counts={'mcp':la.get('mcp',0)-lb.get('mcp',0),'llm':la.get('llm',0)-lb.get('llm',0)})
        if isinstance(inp,dict) and 'code' in inp and 200<=status<300:
            rows=[r for r in after['tables']['grading_submissions'] if r not in before['tables']['grading_submissions']]
            record['input_identity_assertions']['runtime_source_sha256_persisted']=bool(rows) and all(r['code_hash']==hashlib.sha256(inp['code'].encode()).hexdigest() for r in rows)
        if g['route_id'] in ('POST /api/chat','POST /api/chat/stream') and status==200 and isinstance(inp,dict):
            normalized=__import__('unicodedata').normalize('NFC',inp['message']).strip();digest=hashlib.sha256(json.dumps([normalized,None,None],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            rows=[r for r in after['tables']['rag_requests'] if r['request_id']==inp.get('request_id')]
            record['input_identity_assertions']['request_input_hash_persisted']=bool(rows) and all(r['input_hash']==digest for r in rows)
        if media=='text/event-stream':
            record.update(sse_end=validate_sse(body),wire_contract='frozen-react-LF-empty-line-v1')
            # 精确字节另存本地，trace仍使用冻结事件结构；长度/hash不可代替帧比较。
            wire=self.out/'wire'/str(len(self.observations));wire.parent.mkdir(parents=True,exist_ok=True);wire.with_suffix('.sse').write_bytes(raw)
        self.observations.append(record);self.last=after
        print(json.dumps({'case_id':g['case_id'],'status':status,'expected':g['status'],'tables':len(after['tables'])},ensure_ascii=False),flush=True)
        return record
    def safe(self,value):
        if isinstance(value,dict):
            return {k:'<runtime-source>' if k=='code' else '<synthetic-credential>' if k in ('password','old_password','new_password','token') else self.safe(v) for k,v in value.items()}|({'code_sha256':hashlib.sha256(value['code'].encode()).hexdigest()} if 'code' in value else {})
        if isinstance(value,list):return [self.safe(v) for v in value]
        return value
    def collect(self,golden,limit=None):
        initial=snapshot(self.h,self.db);self.bind_initial(golden['business_initial'],initial);self.last=initial
        for i,g in enumerate(golden['observations']):
            if limit is not None and i>=limit:break
            self.request(g)
        final=snapshot(self.h,self.db)
        trace={'version':'p0-v2','baseline_id':BASELINE,'fixture_version':'p0-v2','business_initial':initial,'observations':self.observations,
            'auth_state_step':{'case_id':'auth-vector-group',**step(final,final)},'transaction_cases':[],
            'upstream_vectors':[],'semantic_vectors':{},'state':{'business':final}}
        self.blocked.extend({'kind':'helper_not_yet_implemented','section':s} for s in ('auth-vector-group','transaction_cases(11)','upstream_vectors(14)','semantic_vectors','state.counts/associations/privacy'))
        write(self.out/'java-candidate-trace-p0-v2.json',trace);write(self.out/'collection-blockers.json',self.blocked)
        try:compare(golden,trace);verdict={'status':'pass'}
        except (Difference,InvalidTrace) as e:verdict={'status':'difference','detail':str(e)}
        write(self.out/'strict-verdict.json',verdict)
        return trace,verdict

def main():
    p=argparse.ArgumentParser();p.add_argument('--manifest',required=True,type=Path);p.add_argument('--database',required=True);p.add_argument('--server',required=True,type=Path);p.add_argument('--upstream',required=True);p.add_argument('--out',required=True,type=Path);p.add_argument('--limit',type=int);a=p.parse_args()
    h=PgIsolation.from_env(a.manifest);row=h.manifest['owned'][a.database];db=OwnedDatabase(**{k:row[k] for k in ('name','url','comment','round_id')});h.verify_owned(db)
    golden=json.loads((ROOT/'migration/fixtures/p0-v2/python-golden.json').read_text());c=Collector(h,db,json.loads(a.server.read_text()),a.upstream,a.out);trace,v=c.collect(golden,a.limit)
    print(json.dumps({'observations':len(trace['observations']),'verdict':v,'blockers':len(c.blocked)},ensure_ascii=False));return 0 if v['status']=='pass' and not c.blocked else 1
if __name__=='__main__':raise SystemExit(main())
