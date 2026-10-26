"""冻结186之外的独立HTTP场景；真实业务+外部固定替身，单独owned UI种子库。

只声明可由冻结契约/源规则支持的断言，不把未知结果列为允许差异；任何失败保留。
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import secrets
import unicodedata
from urllib.request import urlopen
from uuid import uuid4
from acceptance.contract.collect import Collector,snapshot,write,ROOT
from acceptance.contract.seed import IDS,RELEASE
from migration.oracle.business_state import step
from migration.oracle.compare import parse_sse,validate_sse

MAX_BYTES=262144

def multipart(fields,filename=None,content=b''):
    boundary='j5-'+secrets.token_hex(12);parts=[]
    for key,value in fields.items():parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+key+'"\r\n\r\n'+str(value)+'\r\n').encode())
    if filename is not None:parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+filename+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+content+b'\r\n')
    parts.append(('--'+boundary+'--\r\n').encode());return b''.join(parts),{'Content-Type':'multipart/form-data; boundary='+boundary}

class Extensions:
    def __init__(self,h,db,server,upstream,out):
        self.h,self.db,self.server,self.upstream,self.out=h,db,server,upstream,Path(out)
        self.client=Collector(h,db,server,upstream,out);self.rows=[];self.failures=[];self.tokens={}
        self.static={r:secrets.token_urlsafe(32) for r in ('viewer','operator','admin','leader')}
        self.spec={t:{'subject':'p5-static-'+r,'roles':[r],'daily_quota':10000} for r,t in self.static.items()}
    def actor_token(self,actor):
        if actor and actor.startswith('static/'):
            return self.static[actor.removeprefix('static/')]
        return self.tokens.get(actor)
    def configure(self,mode):
        st,_,_=self.client.http('POST','/auth-profile',payload={'mode':mode,'tokens':self.spec},base=self.server['control_url'])
        if st!=200:raise RuntimeError('test auth profile configuration failed')
    def fake(self,**settings):self.client.http('POST','/control',payload=settings,base=self.upstream)
    def check(self,cid,method,path,expected,*,actor=None,payload=None,raw=None,headers=None,params=None,predicate=None,no_change=False,zero_upstream=False):
        self.client.http('POST','/clock-case',raw=cid.encode(),base=self.server['control_url'])
        before=snapshot(self.h,self.db);ledger_before=self.client.ledger()
        st,hd,wire=self.client.http(method,path,payload=payload,raw=raw,headers=headers,params=params,token=self.actor_token(actor))
        self.client.settle();after=snapshot(self.h,self.db);ledger_after=self.client.ledger()
        body=json.loads(wire) if hd.get('content-type','').startswith('application/json') else None
        checks={'http_status':st==expected}
        if no_change:checks['zero_business_delta']=before==after
        if zero_upstream:checks['zero_new_external_calls']=ledger_before==ledger_after
        if predicate is not None:
            try:checks.update(predicate(body,wire,hd,before,after))
            except Exception as e:checks['predicate_exception_'+type(e).__name__]=False
        record={'case_id':cid,'method':method,'path':path,'expected_status':expected,'actual_status':st,'checks':checks,
            'body':body,'headers':{k:v for k,v in hd.items() if k not in ('date','set-cookie')},'wire_bytes':len(wire),'wire_sha256':hashlib.sha256(wire).hexdigest(),**step(before,after)}
        self.rows.append(record)
        if not all(checks.values()):self.failures.append({'case_id':cid,'failed_checks':[k for k,v in checks.items() if not v]})
        return body,wire,hd
    def login(self):
        for actor in ('leader','teacher_a','teacher_b','student_a','student_b','operator'):
            body,_,_=self.check('account-login-'+actor,'POST','/api/auth/login',200,payload={'username':IDS[actor],'password':IDS[actor]})
            if not body or not body.get('token'):raise RuntimeError('login failed: '+actor)
            self.tokens[actor]=body['token']
    def mode_roles(self):
        self.configure('account');self.login()
        for actor in ('leader','teacher_a','teacher_b','student_a','student_b','operator'):
            manager=actor in ('leader','teacher_a','teacher_b');grades=manager or actor=='operator'
            for path,status in [('/api/admin/users',200 if manager else 404),('/api/rag/issues',200 if manager else 404),('/api/grading/summary',200 if grades else 404),('/api/assignments',200),('/api/usage/subjects',200 if manager else 404),('/api/sources/p0-history',200)]:
                self.check('account-'+actor+'-'+path,'GET',path,status,actor=actor,no_change=True)
        for role in self.static:
            self.check('account-static-'+role+'-admin','GET','/api/admin/users',200 if role in ('admin','leader') else 404,actor='static/'+role,no_change=True)
            self.check('account-static-'+role+'-subject','GET','/api/usage/me',200,actor='static/'+role,no_change=True,predicate=lambda b,*_,role=role:{'exact_static_subject':b['subject']=='p5-static-'+role})
        # token模式missing bearer重放全部冻结unauthorized面；不只几个示例。
        self.configure('token')
        golden=json.loads((ROOT/'migration/fixtures/p0-v2/python-golden.json').read_text())
        for o in golden['observations']:
            if o['kind']!='unauthorized' or '/api/internal/' in o['route_id']:continue
            req=o['request'];path=req['path_template']
            for key in req.get('path_parameters',{}):path=path.replace('{'+key+'}','p0-missing')
            self.check('token-mode-'+o['case_id'],req['method'],path,o['status'],payload=req.get('input'),params=req.get('params'),no_change=True,zero_upstream=True)
        for role in self.static:
            for path in ('/api/admin/users','/api/assignments','/api/grading/summary'):
                self.check('token-'+role+'-gated-'+path,'GET',path,404,actor='static/'+role,no_change=True)
            self.check('token-'+role+'-source','GET','/api/sources/p0-history',200,actor='static/'+role,no_change=True)
            self.check('token-'+role+'-own-usage','GET','/api/usage/me',200,actor='static/'+role,no_change=True,predicate=lambda b,*_: {'static_subject':b['subject'].startswith('p5-static-')})
        self.configure('local')
        for path in ('/api/admin/users','/api/assignments','/api/grading/summary'):
            self.check('local-gated-'+path,'GET',path,404,no_change=True)
        self.check('local-no-bearer-source','GET','/api/sources/p0-history',200,no_change=True)
        self.check('local-unique-subject','GET','/api/usage/me',200,no_change=True,predicate=lambda b,*_: {'local_subject':b['subject']=='local','admin_role':b['roles']==['admin']})
        self.configure('account')
    def uploads_identity(self):
        assignment,_,_=self.check('teacher-A-assignment-create','POST','/api/assignments',201,actor='teacher_a',payload={'title':'P5合成上传作业','description':'仅隔离验收','deadline':'2099-01-01T00:00:00Z','items':[{'protocol':'dns','points':20}]})
        aid=assignment['id']
        self.check('teacher-B-foreign-assignment','GET','/api/assignments/'+aid,404,actor='teacher_b',no_change=True)
        self.check('student-B-foreign-assignment','GET','/api/assignments/'+aid,404,actor='student_b',no_change=True)
        for filename,content in [('合成_姓名_dns.c',b'int plugin_main(void){return 0;}\xff'),('合成.so',b'\x7fELF\xff\x00synthetic'),('UPPER.C',b'int plugin_main(void){return 0;}')]:
            raw,headers=multipart({'protocol':'dns'},filename,content);expected=hashlib.sha256(content.decode('utf-8',errors='replace').encode()).hexdigest()
            self.check('formal-upload-'+filename,'POST','/api/assignments/'+aid+'/submit',200,actor='student_a',raw=raw,headers=headers,
                predicate=lambda b,w,h,old,new,digest=expected:{'score80':b['score']==80,'execution_hash':any(r['code_hash']==digest for r in new['tables']['grading_submissions'] if r not in old['tables']['grading_submissions'])})
        core=b'int plugin_main(void){return 0;}'
        boundary=b' '*(MAX_BYTES-len(core))+core
        if len(boundary)!=MAX_BYTES:raise RuntimeError('boundary fixture size bug')
        for suffix,payload,status in [('exact',boundary,200),('over',boundary+b'x',413)]:
            raw,headers=multipart({'protocol':'dns'},'边界.c',payload)
            self.check('formal-byte-'+suffix,'POST','/api/assignments/'+aid+'/submit',status,actor='student_a',raw=raw,headers=headers,no_change=status!=200,zero_upstream=status!=200)
        raw,headers=multipart({'message':'DNS','context':'[]'},'合成.c',b'synthetic')
        self.check('chat-context-array-rejected','POST','/api/chat',400,actor='student_a',raw=raw,headers=headers,no_change=True,zero_upstream=True)
        marker=secrets.token_hex(16);source=('/* '+marker+' */\nint plugin_main(void){return 0;}').encode();request=str(uuid4())
        raw,headers=multipart({'message':'DNS cafe\u0301','request_id':request},'cafe\u0301.c',source)
        first,_,_=self.check('chat-nfc-first','POST','/api/chat',200,actor='student_a',raw=raw,headers=headers)
        raw,headers=multipart({'message':'DNS café','request_id':request},'café.c',source)
        self.check('chat-nfc-byte-identical-replay','POST','/api/chat',200,actor='student_a',raw=raw,headers=headers,no_change=True,zero_upstream=True,predicate=lambda b,*_:{'task_identity':b['task_id']==first['task_id'],'session_identity':b['session_id']==first['session_id'],'message_identity':b['message_id']==first['message_id']})
        raw,headers=multipart({'message':'DNS café','request_id':request},'café.c',source+b' ')
        self.check('chat-raw-byte-different-conflict','POST','/api/chat',409,actor='student_a',raw=raw,headers=headers,no_change=True,zero_upstream=True)
        self.fake(plan='grade');raw,headers=multipart({'message':'请评测粘贴代码\n```c\n/* pasted must lose */\n```','request_id':str(uuid4())},'上传原件.c',source)
        self.check('chat-source-priority-upload-over-paste-and-args','POST','/api/chat',200,actor='student_a',raw=raw,headers=headers,
            predicate=lambda b,w,h,old,new:{'grade_source_hash':any(r['code_hash']==hashlib.sha256(source).hexdigest() and r['grade_details']['source_origin']=='upload' for r in new['tables']['grading_submissions'] if r not in old['tables']['grading_submissions']),'terminal_source_cleared':not new['tables']['rag_task_inputs']})
        self.fake()
        self.check('student-A-cannot-read-B-admin','GET','/api/admin/users',404,actor='student_a',no_change=True)
    def six_sources(self):
        self.h.verify_owned(self.db)
        configuration=json.load(urlopen(self.server['control_url']+'/state'))['configuration'];release='p5-six-release'
        with self.h._connect(self.db.name) as c:
            c.execute('INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,6)',(release,configuration,'2026-10-24T04:20:00+00:00'))
            for i in range(6):
                node='li_p5_six_'+str(i);meta={'source_path':'synthetic/six'+str(i//2)+'.md','section':str(i),'title':'P5多轮来源'}
                c.execute('INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)',(node,'DNS 解析报文中的域名',json.dumps(meta,ensure_ascii=False),str([1.0]+[0.0]*1023)))
                c.execute('INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)',(release,node))
            c.execute("UPDATE rag_index_state SET release_id=%s WHERE name='active'",(release,))
        self.fake(tool_rounds=2)
        answer,_,_=self.check('chat-six-sources-two-tool-rounds','POST','/api/chat',200,actor='student_a',payload={'message':'DNS做什么','request_id':str(uuid4())},predicate=lambda b,*_:{'six_citations':len(b['citations'])==6,'unique_chunks':len({r['chunk_id'] for r in b['citations']})==6,'same_file_fragments_kept':len({r['source_path'] for r in b['citations']})==3})
        citations=answer['citations'];refs=[r['chunk_id'] for r in citations]
        ledger=json.load(urlopen(self.upstream+'/ledger'))['calls'];ref_rows=[r['refs'] for r in ledger if r['kind']=='tool_refs' and len(r.get('refs',[]))==6]
        if not ref_rows or any(sorted(r)!=list(range(1,7)) for r in ref_rows):self.failures.append({'case_id':'cross-tool-ref-identity','failed_checks':['stable_refs_1_to_6']})
        _,wire,_=self.check('terminal-six-SSE-replay-zero-upstream','GET','/api/tasks/'+answer['task_id']+'/events',200,actor='student_a',no_change=True,zero_upstream=True)
        events=parse_sse([wire]);validate_sse(events);sources=next(e['data']['citations'] for e in events if e['event']=='sources')
        if [r['chunk_id'] for r in sources]!=refs:self.failures.append({'case_id':'terminal-six-SSE-order','failed_checks':['same_chunk_order']})
        with self.h._connect(self.db.name) as c:c.execute("UPDATE rag_index_state SET release_id=%s WHERE name='active'",(RELEASE,))
        for citation in citations:
            self.check('historical-six-source-'+citation['chunk_id'],'GET','/api/sources/'+citation['chunk_id'],200,actor='student_a',no_change=True,predicate=lambda b,w,h,old,new,expected=citation:{'source_path':b['source_path']==expected['source_path'],'section':b['section']==expected['section']})
        self.fake()
    def run(self):
        try:self.mode_roles();self.uploads_identity();self.six_sources()
        except Exception as e:self.failures.append({'case_id':'extension-runner-abort','error_type':type(e).__name__,'detail':str(e)})
        write(self.out/'extension-trace.json',self.rows);write(self.out/'extension-summary.json',{'cases':len(self.rows),'failures':self.failures,'scope':'冻结186之外独立HTTP角色/模式/输入/来源场景；不替代全量oracle/UI验收'})
        return not self.failures
