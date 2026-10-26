"""只对本轮localhost HTTPS的合成业务探针；不保存token或原始响应日志。"""
import hashlib
import json
import secrets
import ssl
import time
import urllib.error
import urllib.request
import uuid

class Probes:
    def __init__(self,cert):
        self.context=ssl.create_default_context(cafile=str(cert));self.origin='https://localhost:18443'
    def request(self,path,*,body=None,token=None,expected=200,method=None):
        if not path.startswith('/'):raise ValueError('only fixed-origin paths')
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(self.origin+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try:
            response=urllib.request.urlopen(request,context=self.context,timeout=120)
        except urllib.error.HTTPError as error:response=error
        with response:
            raw=response.read();status=response.status;headers=dict(response.headers)
        if status!=expected:raise AssertionError('unexpected HTTP '+str(status)+' at fixed probe '+path.split('?')[0])
        return raw,headers
    def json(self,path,**kwargs):
        raw,_=self.request(path,**kwargs);return json.loads(raw) if raw else None
    def health(self,*,ready=True,timeout=120):
        started=time.monotonic();last=None
        while time.monotonic()-started<timeout:
            try:self.json('/api/ready' if ready else '/api/health');return time.monotonic()-started
            except Exception as error:last=error;time.sleep(.25)
        raise AssertionError('health/ready did not recover') from last
    def ingress(self):
        raw,headers=self.request('/')
        if b'<html' not in raw.lower():raise AssertionError('frontend did not serve HTML')
        self.request('/metrics',expected=404);self.request('/actuator/health',expected=404)
        return {'https_verified':True,'frontend_html':True,'metrics_external_status':404,'actuator_external_status':404,
            'nosniff':headers.get('X-Content-Type-Options')=='nosniff'}
    def login(self,username,password):return self.json('/api/auth/login',body={'username':username,'password':password})['token']
    def new_business(self,username):
        token=self.login(username,username);password='J5!'+secrets.token_urlsafe(24)
        self.request('/api/auth/change-password',body={'old_password':username,'new_password':password},token=token,expected=204)
        result=self.json('/api/chat',body={'message':'解释合成DNS协议与来源引用','request_id':str(uuid.uuid4()),'context':{}},token=token)
        for key in ('session_id','message_id','task_id'):
            if not result.get(key):raise AssertionError('missing committed chat identity')
        if not result.get('answer'):raise AssertionError('empty answer')
        if not result.get('citations'):raise AssertionError('deployment source citation missing')
        state={'username':username,'password':password,'session_id':result['session_id'],'message_id':result['message_id'],
            'task_id':result['task_id'],'answer_sha256':hashlib.sha256(result['answer'].encode()).hexdigest(),
            'citation_ids':[x['chunk_id'] for x in result['citations']]}
        self.read_existing(state);return state
    def read_existing(self,state):
        token=self.login(state['username'],state['password'])
        history=self.json('/api/chat/sessions/'+state['session_id'],token=token)
        messages=history.get('messages',[])
        message=next((row for row in messages if row['id']==state['message_id']),None)
        if message is None:raise AssertionError('persisted message not readable')
        if hashlib.sha256(message['content'].encode()).hexdigest()!=state['answer_sha256']:raise AssertionError('persisted answer changed')
        if [row['chunk_id'] for row in message.get('citations',[])]!=state['citation_ids']:raise AssertionError('old citation identity changed')
        for citation in message.get('citations',[]):
            if not citation.get('source_path'):raise AssertionError('old source no longer resolves')
        return {'persisted_message_readable':True,'citation_ids_preserved':True,'citation_count':len(state['citation_ids'])}
    def sse(self,state):
        token=self.login(state['username'],state['password'])
        request=urllib.request.Request(self.origin+'/api/chat/stream',headers={'Content-Type':'application/json','Authorization':'Bearer '+token},
            data=json.dumps({'message':'解释合成DNS协议','request_id':str(uuid.uuid4()),'context':{}}).encode())
        events=[];started=time.monotonic();first=None
        with urllib.request.urlopen(request,context=self.context,timeout=120) as response:
            if 'text/event-stream' not in response.headers.get('Content-Type',''):raise AssertionError('not SSE')
            for raw in response:
                if raw.startswith(b'event:'):
                    if first is None:first=time.monotonic()-started
                    event=raw.decode().split(':',1)[1].strip();events.append(event)
                    if event=='done':break
        if len(events)<2 or events[-1]!='done':raise AssertionError('missing progressive/terminal SSE frames')
        return {'events':events,'first_event_seconds':first,'terminal_seconds':time.monotonic()-started,'domain':'TLS+nginx+HTTP stream observation, not model quality'}
