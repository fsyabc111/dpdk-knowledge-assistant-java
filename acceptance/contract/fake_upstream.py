"""只绑loopback的固定外部协议替身；账本仅tool/stage/计数/hash，不落源码或消息。"""
import argparse
from collections import Counter
import hashlib
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json
from pathlib import Path
import threading
import time

class ScriptState:
    def __init__(self): self.lock=threading.Lock();self.calls=[];self.jobs={};self.delay=0;self.tool_rounds=1;self.plan="kb"
    def record(self, **row):
        with self.lock:self.calls.append(row)
    def snapshot(self):
        with self.lock:return {"calls":list(self.calls),"counts":dict(Counter(x['kind'] for x in self.calls))}

STATE=ScriptState()
def grade(protocol):
    return {'ok':True,'protocol':protocol,'score':80,'passed':4,'cases_total':5,'failed':1,'crashed':0,'timedout':0,'cheat':0,
        'results':[{'name':'synthetic-case','result':'FAIL','category':'basic','failures':[{'desc':'synthetic field','expected':'x','actual':'y'}]}]}

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def respond(self, payload, status=200):
        data=json.dumps(payload,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def do_GET(self):
        if self.path=='/ledger':return self.respond(STATE.snapshot())
        return self.respond({'detail':'not found'},404)
    def request_body(self):
        if self.headers.get('Transfer-Encoding','').lower()=='chunked':
            chunks=[];size_total=0
            while True:
                size=int(self.rfile.readline().split(b';',1)[0].strip(),16)
                if size==0:
                    while self.rfile.readline().strip():pass
                    break
                size_total+=size
                if size_total>1024*1024:raise ValueError('request too large')
                chunks.append(self.rfile.read(size))
                if self.rfile.read(2)!=b'\r\n':raise ValueError('invalid chunk framing')
            return b''.join(chunks)
        size=int(self.headers.get('Content-Length','0'))
        if size>1024*1024:raise ValueError('request too large')
        return self.rfile.read(size)
    def do_POST(self):
        try: data=json.loads(self.request_body() or b'{}')
        except Exception:return self.respond({'detail':'invalid JSON'},400)
        if self.path=='/control':
            STATE.delay=float(data.get('delay',0));STATE.tool_rounds=int(data.get('tool_rounds',1));STATE.plan=str(data.get('plan','kb'));return self.respond({'ok':True})
        if self.path.endswith('/embeddings'):
            texts=data.get('input',[]);texts=[texts] if isinstance(texts,str) else texts
            STATE.record(kind='embedding',items=len(texts));return self.respond({'data':[{'index':i,'embedding':[1.0]+[0.0]*1023} for i,_ in enumerate(texts)],'usage':{'prompt_tokens':len(texts),'total_tokens':len(texts)}})
        if self.path.endswith('/chat/completions'):
            STATE.record(kind='llm',tool_definitions=len(data.get('tools',[])))
            if STATE.delay:time.sleep(STATE.delay)
            messages=data.get('messages',[]);last_user=max((i for i,x in enumerate(messages) if x.get('role')=='user'),default=-1)
            tools=[x for x in messages[last_user+1:] if x.get('role')=='tool'];has_tool=len(tools)>=STATE.tool_rounds
            if tools:
                for item in tools:
                    try:
                        value=json.loads(item.get('content','{}'));chunks=value.get('data',{}).get('chunks',[])
                        if chunks:STATE.record(kind='tool_refs',refs=[c.get('ref') for c in chunks],sections=[c.get('section') for c in chunks])
                    except (ValueError,AttributeError):pass
            text=str(messages)
            if data.get('tools') and not has_tool:
                msg={'role':'assistant','content':'','tool_calls':[{'id':'p0-call-1' if STATE.tool_rounds==1 else 'p5-call-'+str(len(tools)+1),'type':'function','function':{'name':'grade_plugin' if STATE.plan=='grade' else 'kb_search','arguments':'{"protocol":"dns","plugin_source":"/* model echo is not the execution source */"}' if STATE.plan=='grade' else '{"query":"DNS"}'}}]};tokens=(20,4)
            elif not data.get('tools') and messages and 'supported' in str(messages[0].get('content','')):msg={'role':'assistant','content':'{"status":"answered","supported":true}'};tokens=(20,4)
            else:msg={'role':'assistant','content':'评测结果为80分。' if STATE.plan=='grade' else 'DNS 解析报文中的域名 [1]。'};tokens=(30,8)
            return self.respond({'id':'j5-fixed','object':'chat.completion','model':'synthetic-model','choices':[{'index':0,'message':msg,'finish_reason':'tool_calls' if 'tool_calls' in msg else 'stop'}], 'usage':{'prompt_tokens':tokens[0],'completion_tokens':tokens[1],'total_tokens':sum(tokens)}})
        if self.path.startswith('/mcp'):
            method=data.get('method');rid=data.get('id')
            if method=='initialize':result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'j5-fixed-harness','version':'1'}}
            elif method=='tools/list':result={'tools':[{'name':n,'description':'合成替身','inputSchema':{'type':'object'}} for n in ['list_protocols','grade_plugin','get_task_status','get_reference_answer','get_protocol_spec']]}
            elif method=='tools/call':
                p=data.get('params',{});name=p.get('name');args=p.get('arguments',{});row={'kind':'mcp','tool':name,'job_id':args.get('job_id')}
                source=args.get('plugin_source',args.get('source',''))
                if source:row.update(source_bytes=len(source.encode()),source_sha256=hashlib.sha256(source.encode()).hexdigest())
                STATE.record(**row)
                if name=='list_protocols':value={'ok':True,'protocols':[{'name':'dns','cases':5,'categories':{'basic':5}}]}
                elif name=='grade_plugin':
                    value=grade(args.get('protocol','dns'))
                    if args.get('job_id'):STATE.jobs[args['job_id']]={'status':'finished','result':value}
                elif name=='get_task_status':value={'ok':True,'job_id':args.get('job_id'),**STATE.jobs.get(args.get('job_id'),{'status':'unknown'})}
                else:value={'ok':False,'error':'synthetic_no_reference'}
                result={'content':[{'type':'text','text':json.dumps(value,ensure_ascii=False)}],'isError':False}
            elif method and method.startswith('notifications/'):
                self.send_response(202);self.end_headers();return
            else:return self.respond({'jsonrpc':'2.0','id':rid,'error':{'code':-32601,'message':'unknown method'}})
            return self.respond({'jsonrpc':'2.0','id':rid,'result':result})
        return self.respond({'detail':'not found'},404)

def main():
    p=argparse.ArgumentParser();p.add_argument('--state',required=True,type=Path);p.add_argument('--port',type=int,default=0);args=p.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler);args.state.parent.mkdir(parents=True,exist_ok=True);args.state.write_text(json.dumps({'base_url':'http://127.0.0.1:'+str(server.server_port)}));server.serve_forever()
if __name__=='__main__':main()
