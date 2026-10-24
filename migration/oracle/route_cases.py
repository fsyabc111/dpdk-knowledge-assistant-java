"""显式路由用例编排；输入/前置/上游脚本/期望与实际观测一起记录。"""
from __future__ import annotations
import asyncio
import hashlib
import json
import secrets
import re
from uuid import NAMESPACE_URL, uuid5
from .compare import parse_sse
from .ports import MCP_LEDGER, LLM_LEDGER
from .runtime import IDS


class Cases:
    def __init__(self,client):
        self.client=client;self.tokens={};self.observations=[];self.source_marker=secrets.token_hex(16)
        self.source='/* P0 '+self.source_marker+' */\nint plugin_main(void){return 0;}\n'
    async def request(self,route,kind,path=None,*,who='leader',body=None,status=None,headers=None,params=None,precondition='seeded-synthetic-tree'):
        method,template=route.split(' ',1)
        path=path or template
        hdr={}
        if who in self.tokens:hdr['Authorization']='Bearer '+self.tokens[who]
        if who=='maintenance':hdr['X-Maintenance-Key']='p0-synthetic-maintenance'
        if headers:hdr.update(headers)
        before=(len(MCP_LEDGER),len(LLM_LEDGER))
        response=await self.client.request(method,path,json=body,headers=hdr,params=params)
        media=response.headers.get('content-type','').split(';')[0]
        if method=='HEAD' or response.status_code==204:payload=None
        elif media=='text/event-stream':payload=parse_sse([response.content])
        elif media=='application/json':payload=response.json()
        elif path=='/metrics':
            payload={'families':[line for line in response.text.splitlines() if line.startswith('# TYPE ')]}
        else:payload={'sha256':hashlib.sha256(response.content).hexdigest(),'length':len(response.content)}
        selected={k:response.headers[k] for k in ['www-authenticate','allow','access-control-allow-origin',
          'access-control-allow-methods','access-control-allow-headers','access-control-allow-credentials',
          'x-content-type-options','x-frame-options','referrer-policy','content-security-policy','x-accel-buffering','cache-control'] if k in response.headers}
        record={'case_id':route+'::'+kind,'route_id':route,'kind':kind,'precondition':precondition,
            'request':{'method':method,'path_template':template,'path_parameters':dict(zip(re.findall(r'{([^}]+)}',template),[b for a,b in zip(template.split('/'),path.split('/')) if a.startswith('{')])),'actor':who,'input':self._safe(body),'params':params},
            'upstream_script':'fixed-kb-tool-call-and-answer/mcp-grade-80',
            'status':response.status_code,'expected_status':status,'media_type':media,'headers':selected,'body':payload,
            'side_effect_counts':{'mcp':len(MCP_LEDGER)-before[0],'llm':len(LLM_LEDGER)-before[1]}}
        self.observations.append(record)
        if status is not None:assert response.status_code==status,(route,kind,response.status_code,response.text[:500])
        return payload
    def _safe(self,value):
        if isinstance(value,dict):return {k:('<runtime-source>' if k=='code' else '<synthetic-credential>' if k in {'password','old_password','new_password','token'} else self._safe(v)) for k,v in value.items()}
        return value


async def success(c,seeds):
    # 真实账号登录；每个角色保留独立token，客户端地址限流桶在固定合成窗内重置。
    from app.api.auth import _login_rate_limiter,_reset_rate_limiter
    for who,uid in IDS.items():
        _login_rate_limiter._buckets.clear()
        reply=await c.request('POST /api/auth/login','success-'+who,who='',body={'username':uid,'password':uid},status=200)
        c.tokens[who]=reply['token']
    for route in ['GET /api/admin/users','GET /api/admin/audit-log','GET /api/admin/password-reset-requests',
      'GET /api/assignments','GET /api/assignments/protocols','GET /api/grading/submissions','GET /api/grading/summary',
      'GET /api/rag/issues','GET /api/rag/knowledge','GET /api/usage/me','GET /api/usage/subjects','GET /api/usage/cost',
      'GET /api/health','GET /api/ready','GET /openapi.json','GET /docs','GET /docs/oauth2-redirect','GET /redoc',
      'HEAD /openapi.json','HEAD /docs','HEAD /docs/oauth2-redirect','HEAD /redoc','GET /metrics']:
        await c.request(route,'success',status=200)
    await c.request('GET /api/internal/session-storage','success',who='maintenance',status=200)
    await c.request('POST /api/internal/purge-sessions','success',who='maintenance',params={'dry_run':'true'},status=200)
    await c.request('POST /api/plugin-template','success',body={'protocol':'dns'},status=200)
    await c.request('GET /api/sources/{chunk_id}','success','/api/sources/p0-history',who='student_a',status=200)
    # 账号树、配额、密码重置、批量操作均走真实路由服务。
    new='U20270000061'
    await c.request('POST /api/admin/users','success',body={'ids':[new]},status=200)
    await c.request('PATCH /api/admin/users/{user_id}','success',f'/api/admin/users/{new}',body={'daily_quota':5},status=200)
    await c.request('POST /api/admin/users/assign','success',body={'ids':[new],'created_by':IDS['teacher_a']},status=200)
    await c.request('POST /api/admin/users/{user_id}/status','success',f'/api/admin/users/{new}/status',body={'enabled':False},status=204)
    await c.request('POST /api/admin/users/{user_id}/reset-password','success',f'/api/admin/users/{new}/reset-password',status=204)
    await c.request('POST /api/admin/users/{user_id}/set-password','success',f'/api/admin/users/{new}/set-password',body={'new_password':'p0-synthetic-password'},status=204)
    await c.request('POST /api/auth/change-password','success',who='free',body={'old_password':IDS['free'],'new_password':'p0-synthetic-password'},status=204)
    await c.request('POST /api/auth/password-reset-requests','success',who='',body={'username':IDS['free']},status=202)
    requests=await c.request('GET /api/admin/password-reset-requests','success-after-request',status=200)
    resetid=requests['requests'][0]['id']
    approved=await c.request('POST /api/admin/password-reset-requests/{request_id}/approve','success',f'/api/admin/password-reset-requests/{resetid}/approve',status=200)
    await c.request('POST /api/auth/password-reset','success',who='',body={'token':approved['reset_token'],'new_password':'p0-synthetic-reset'},status=204)
    _reset_rate_limiter._buckets.clear()
    await c.request('POST /api/auth/password-reset-requests','success-second',who='',body={'username':IDS['student_b']},status=202)
    requests=await c.request('GET /api/admin/password-reset-requests','success-second',status=200)
    await c.request('POST /api/admin/password-reset-requests/{request_id}/reject','success',f"/api/admin/password-reset-requests/{requests['requests'][0]['id']}/reject",status=204)
    await c.request('POST /api/admin/users/delete','success',body={'ids':[IDS['delete']],'password':IDS['leader']},status=200)
    # 作业提交与讲解分别独立调用；源码仅在运行时内存生成。
    assignment=await c.request('POST /api/assignments','success',who='teacher_a',body={'title':'合成DNS作业','description':'P0','deadline':'2099-01-01T00:00:00Z','items':[{'protocol':'dns','points':20}]},status=201)
    aid=assignment['id']
    await c.request('GET /api/assignments/{assignment_id}','success',f'/api/assignments/{aid}',who='student_a',status=200)
    await c.request('PATCH /api/assignments/{assignment_id}','success',f'/api/assignments/{aid}',who='teacher_a',body={'title':'合成DNS作业修订'},status=200)
    submission=await c.request('POST /api/assignments/{assignment_id}/submit','success',f'/api/assignments/{aid}/submit',who='student_a',body={'protocol':'dns','code':c.source},status=200)
    for suffix in ['grades','export']:
        await c.request(f'GET /api/assignments/{{assignment_id}}/{suffix}','success',f'/api/assignments/{aid}/{suffix}',who='teacher_a',status=200)
    explain=await c.request('POST /api/assignments/{assignment_id}/submissions/{submission_id}/explain','success',f"/api/assignments/{aid}/submissions/{submission['submission_id']}/explain",who='student_a',status=200)
    from app.services import task_service
    managed=task_service.TASKS.get(explain['task_id'])
    if managed:await asyncio.wait_for(managed.done.wait(),20)
    await c.request('POST /api/assignments/{assignment_id}/withdraw','success',f'/api/assignments/{aid}/withdraw',who='teacher_a',status=200)
    await c.request('POST /api/assignments/{assignment_id}/reactivate','success',f'/api/assignments/{aid}/reactivate',who='teacher_a',status=200)
    knowledge=await c.request('POST /api/rag/knowledge','success',body={'title':'合成DNS','body':'DNS解析域名','shared_confirmed':True},status=200)
    kid=knowledge['id']
    await c.request('PATCH /api/rag/knowledge/{knowledge_id}','success',f'/api/rag/knowledge/{kid}',body={'title':'合成DNS修订','body':'DNS解析域名','shared_confirmed':True,'revision':1},status=200)
    await c.request('GET /api/rag/knowledge/{knowledge_id}/revisions','success',f'/api/rag/knowledge/{kid}/revisions',status=200)
    task=seeds['failed_task']
    await c.request('GET /api/rag/issues/{issue_id}','success',f'/api/rag/issues/{task}',status=200)
    await c.request('PATCH /api/rag/issues/{issue_id}','success',f'/api/rag/issues/{task}',body={'status':'in_progress','claim':True,'note':'合成审核'},status=200)
    await c.request('POST /api/rag/feedback','success',who='student_a',body={'message_id':seeds['failed_message'],'reason':'unresolved','detail':'合成反馈'},status=200)
    await c.request('POST /api/rag/issues/{issue_id}/verify','success',f'/api/rag/issues/{task}/verify',status=200)
    # 实际 LangGraph + kb_search + 固定PG向量 + 校验 + 原子终态持久化。
    chat=await c.request('POST /api/chat','success',who='student_a',body={'message':'DNS 做什么','request_id':str(uuid5(NAMESPACE_URL,'p0:chat'))},status=200)
    assert chat['answer_status']=='answered',chat
    sid,tid=chat['session_id'],chat['task_id']
    await c.request('POST /api/chat/stream','success',who='student_a',body={'message':'DNS 解析什么','request_id':str(uuid5(NAMESPACE_URL,'p0:stream'))},status=200)
    await c.request('GET /api/chat/sessions','success',who='student_a',status=200)
    await c.request('GET /api/chat/sessions/{session_id}','success',f'/api/chat/sessions/{seeds["history_session"]}',who='student_a',status=200)
    await c.request('GET /api/tasks/active','success',who='student_a',params={'session_id':sid},status=200)
    await c.request('GET /api/tasks/{task_id}','success',f'/api/tasks/{tid}',who='student_a',status=200)
    await c.request('GET /api/tasks/{task_id}/events','success',f'/api/tasks/{tid}/events',who='student_a',status=200)
    await c.request('POST /api/tasks/{task_id}/cancel','success',f'/api/tasks/{tid}/cancel',who='student_a',status=200)
    await c.request('DELETE /api/chat/sessions/{session_id}','success',f'/api/chat/sessions/{sid}',who='student_a',status=204)
    await c.request('POST /api/auth/logout','success',who='disabled',status=204)
    return {'assignment_id':aid,'submission_id':submission['submission_id'],'chat':chat,'knowledge_id':kid}


async def negatives(c,routes,state,seeds):
    no_auth_prefix=('/api/auth/login','/api/auth/password-reset','/api/auth/password-reset-requests')
    public=('/api/health','/api/ready','/metrics','/docs','/docs/oauth2-redirect','/openapi.json','/redoc')
    mapping=[]
    for route in routes:
        method,path=route.split(' ',1)
        concrete=path
        replacements={'session_id':seeds['history_session'],'task_id':seeds['failed_task'],
           'assignment_id':state['assignment_id'],'submission_id':state['submission_id'],
           'user_id':IDS['student_a'],'request_id':'p0-missing','issue_id':seeds['failed_task'],
           'knowledge_id':state['knowledge_id'],'chunk_id':'p0-history'}
        for key,value in replacements.items():concrete=concrete.replace('{'+key+'}',value)
        cases=[o['case_id'] for o in c.observations if o['route_id']==route and o['kind'].startswith('success')]
        assert cases,('缺成功场景',route)
        entry={'route_id':route,'success':{'status':'通过','case_ids':cases},'unauthorized':{},'invalid_input':{},'retry_concurrency':{}}
        if path in public or path in no_auth_prefix:
            entry['unauthorized']={'status':'明确不适用','reason':'冻结路由公开或匿名账户恢复入口；不要求Bearer。账户枚举由专门反例验证。'}
        else:
            status=403 if path.startswith('/api/internal/') else 401
            o=await c.request(route,'unauthorized',concrete,who='',body={},status=status)
            entry['unauthorized']={'status':'通过','case_ids':[route+'::unauthorized'],'expected_status':status}
        # body schema 或手工JSON解析失败；无body路由只在声明了query/资源ID时覆盖无效输入。
        body_routes=method in {'POST','PATCH'} and path not in {
            '/api/auth/logout','/api/admin/users/{user_id}/reset-password',
            '/api/admin/password-reset-requests/{request_id}/approve','/api/admin/password-reset-requests/{request_id}/reject',
            '/api/assignments/{assignment_id}/withdraw','/api/assignments/{assignment_id}/reactivate',
            '/api/assignments/{assignment_id}/submissions/{submission_id}/explain',
            '/api/internal/purge-sessions','/api/rag/issues/{issue_id}/verify','/api/tasks/{task_id}/cancel'}
        if body_routes and path != '/api/assignments/{assignment_id}' and path != '/api/admin/users/{user_id}':
            await c.request(route,'invalid-input',concrete,who='student_a' if path in {'/api/chat','/api/chat/stream','/api/auth/change-password','/api/rag/feedback'} or path.endswith('/submit') else 'leader',body={},status=422)
            entry['invalid_input']={'status':'通过','case_ids':[route+'::invalid-input'],'reason':'缺少必填字段'}
        elif path=='/api/admin/users/{user_id}':
            await c.request(route,'invalid-input',concrete,body={'daily_quota':-1},status=422)
            entry['invalid_input']={'status':'通过','case_ids':[route+'::invalid-input'],'reason':'配额不能为负数'}
        elif path=='/api/assignments/{assignment_id}' and method=='PATCH':
            await c.request(route,'invalid-input',concrete,body={'title':''},status=422)
            entry['invalid_input']={'status':'通过','case_ids':[route+'::invalid-input'],'reason':'标题最小长度'}
        elif (method=='GET' or path=='/api/internal/purge-sessions') and path in {'/api/chat/sessions','/api/chat/sessions/{session_id}','/api/rag/issues','/api/grading/submissions','/api/usage/cost','/api/tasks/active','/api/internal/purge-sessions'}:
            params={'limit':0} if path in {'/api/chat/sessions','/api/chat/sessions/{session_id}','/api/rag/issues','/api/grading/submissions'} else {'days':0} if path=='/api/usage/cost' else {'session_id':''} if path=='/api/tasks/active' else {'retention_days':0}
            await c.request(route,'invalid-input',concrete,who='maintenance' if path.startswith('/api/internal') else 'leader',params=params,status=422)
            entry['invalid_input']={'status':'通过','case_ids':[route+'::invalid-input'],'reason':'声明查询参数边界'}
        elif '{' in path:
            invalid=re.sub(r'{[^}]+}','p0-missing',path)
            # revisions 不校验条目存在性，空数组是冻结行为。
            expected=200 if path.endswith('/revisions') else 404
            await c.request(route,'invalid-resource',invalid,who='student_a' if path.startswith('/api/tasks/') or path.startswith('/api/chat/') or path.endswith('/submit') or '/explain' in path else 'leader',status=expected)
            entry['invalid_input']={'status':'通过','case_ids':[route+'::invalid-resource'],'reason':'未知资源标识；revisions缺失条目返回空数组'}
        else:
            entry['invalid_input']={'status':'明确不适用','reason':'冻结端点无可校验body/query/path输入；未知query被框架忽略，不伪装422。'}
        if path in {'/api/chat','/api/chat/stream','/api/tasks/{task_id}/cancel','/api/tasks/{task_id}/events'}:
            entry['retry_concurrency']={'status':'通过','case_ids':['pg-admission-race','pg-finish-once','pg-cancel-idempotent'],'reason':'真实PG服务级身份/终态/取消原语；HTTP接续重放另有专例'}
        elif method in {'GET','HEAD'}:
            entry['retry_concurrency']={'status':'明确不适用','reason':'只读端点，不产生业务副作用；无重试状态机'}
        elif path.endswith('/submit'):
            entry['retry_concurrency']={'status':'未执行','reason':'正式submit无业务幂等键；保留重复提交风险，不扩展chat去重承诺','owner':'J2-03/J5-01'}
        else:
            entry['retry_concurrency']={'status':'未执行','reason':'已运行基础成功/权限/输入分支；重试或并发完整分支留待对应业务卡差分扩展','owner':'对应coverage-map.owner_task/J5-01'}
        mapping.append(entry)
    # 两班树隔离与对象隐匿：有效他人对象与不存在对象必须相同。
    for route,path in [('GET /api/chat/sessions/{session_id}',f'/api/chat/sessions/{seeds["history_session"]}'),
                       ('GET /api/tasks/{task_id}',f'/api/tasks/{seeds["failed_task"]}'),
                       ('GET /api/assignments/{assignment_id}',f'/api/assignments/{state["assignment_id"]}')]:
        await c.request(route,'foreign-tree',path,who='student_b',status=404)
    await c.request('GET /api/admin/users','viewer-hidden',who='student_a',status=404)
    await c.request('GET /api/rag/issues/{issue_id}','other-teacher-hidden',f'/api/rag/issues/{seeds["failed_task"]}',who='teacher_b',status=404)
    # CORS transport guard 与未声明业务 HEAD/普通 OPTIONS。
    for origin,expected in [('http://127.0.0.1:5174',200),('https://p0.invalid',400)]:
        await c.request('OPTIONS /api/chat','cors-'+str(expected),'/api/chat',who='',headers={'Origin':origin,'Access-Control-Request-Method':'POST'},status=expected)
    await c.request('HEAD /api/health','business-head','/api/health',who='',status=405)
    await c.request('OPTIONS /api/health','ordinary-options','/api/health',who='',status=405)
    # 真正HTTP重放：相同身份不调用模型、不扣第二次配额。
    payload={'message':'DNS 固定重放问题','request_id':str(uuid5(NAMESPACE_URL,'p0:retry'))}
    first=await c.request('POST /api/chat','retry-first',who='student_a',body=payload,status=200)
    replay=await c.request('POST /api/chat','retry-replay',who='student_a',body=payload,status=200)
    assert all(first[k]==replay[k] for k in ['session_id','task_id','message_id','request_id','answer','answer_status','answer_reason'])
    assert [x['chunk_id'] for x in first['citations']]==[x['chunk_id'] for x in replay['citations']]
    assert replay['next_steps']==[] and all(x['title']=='' and x['score']==0 for x in replay['citations'])
    assert c.observations[-1]['side_effect_counts']=={'mcp':0,'llm':0}
    await c.request('POST /api/chat','retry-identity-conflict',who='student_a',body={**payload,'message':'不同问题'},status=409)
    return mapping
