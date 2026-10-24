"""在真实冻结客户端上注入本地协议故障；提交未知不能盲重试。"""
from __future__ import annotations
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import patch


async def run():
    import httpx
    from app.config import get_settings
    from app.core.llm_client import OpenAICompatibleClient
    from app.core.usage import UsageRecorder
    from app.agent import mcp_client as mcp
    results=[]
    settings=get_settings().model_copy(update={'llm_max_retries':1,'llm_retry_base_seconds':0.001})
    def result(name,**details):results.append({'case_id':name,'status':'通过',**details})
    class Bytes(httpx.AsyncByteStream):
        def __init__(self,parts,fail=False):self.parts,self.fail=parts,fail
        async def __aiter__(self):
            for part in self.parts:yield part
            if self.fail:raise httpx.ReadError('P0 synthetic disconnect')
    usage_frame={'choices':[],'usage':{'prompt_tokens':3,'completion_tokens':2,'total_tokens':5}}
    frame=lambda data:('data: '+json.dumps(data,ensure_ascii=False)+'\n\n').encode()
    for script in ['multi_chunk','bad_json','disconnect','429','timeout','usage_missing','usage_duplicate']:
        calls=0
        async def handle(request):
            nonlocal calls
            calls+=1
            if script=='timeout':raise httpx.ReadTimeout('P0 synthetic timeout',request=request)
            if script=='429':
                if calls==1:return httpx.Response(429,json={'error':'synthetic_rate_limit'})
                return httpx.Response(200,json={'choices':[{'message':{'content':'synthetic'}}],'usage':{'prompt_tokens':3,'completion_tokens':2,'total_tokens':5}})
            if script=='bad_json':return httpx.Response(200,text='{',headers={'content-type':'application/json'})
            parts=[frame({'choices':[{'delta':{'content':'DNS '}}]}),frame({'choices':[{'delta':{'content':'合成'}}]})]
            if script!='usage_missing':parts.append(frame(usage_frame))
            if script=='usage_duplicate':parts.append(frame(usage_frame))
            if script!='disconnect':parts.append(b'data: [DONE]\n\n')
            return httpx.Response(200,headers={'content-type':'text/event-stream'},stream=Bytes(parts,script=='disconnect'))
        transport=httpx.MockTransport(handle)
        async with httpx.AsyncClient(transport=transport) as http:
            recorder=UsageRecorder()
            with patch.object(OpenAICompatibleClient,'_llm_client',http):
                client=OpenAICompatibleClient(settings)
                try:
                    if script in {'bad_json','429','timeout'}:await client.chat([{'role':'user','content':'synthetic'}],usage=recorder,stage='p0-protocol')
                    else:
                        deltas=[d async for d in client.stream_chat([{'role':'user','content':'synthetic'}],usage=recorder,stage='p0-protocol')]
                        assert ''.join(deltas)=='DNS 合成'
                except Exception:
                    assert script in {'bad_json','disconnect','timeout'},script
                else:assert script not in {'bad_json','disconnect','timeout'},script
            assert len(recorder.entries)==1,(script,recorder.entries)
            entry=recorder.entries[0]
            if script=='usage_missing':assert entry.total_tokens is None and entry.usage_source=='unknown'
            if script=='usage_duplicate':assert entry.total_tokens==5 and entry.call_count==1
            if script=='429':assert calls==2
            if script=='timeout':assert calls==2
            if script=='disconnect':assert calls==1
            result('llm-'+script,attempts=calls,usage_source=entry.usage_source,total_tokens=entry.total_tokens,
                   assertion='真实OpenAICompatibleClient脚本化传输；缺失unknown、重复usage不重复计费、已吐delta不重试')
    # 真正客户端的建连/initialize/提交边界，替身只提供MCP传输和会话。
    for script in ['connect_failure','initialize_failure','submitted_response_lost','finished','running','unknown','restart_clears_ledger']:
        ledger={'connects':0,'submits':0,'jobs':{'p0-job':{'status':'finished','result':{'ok':True,'score':80}}}}
        if script=='restart_clears_ledger':ledger['jobs'].clear()
        class Transport:
            async def __aenter__(self):
                ledger['connects']+=1
                if script=='connect_failure' and ledger['connects']<3:raise ConnectionError('P0 synthetic connect')
                return None,None,None
            async def __aexit__(self,*args):return False
        class Session:
            async def __aenter__(self):return self
            async def __aexit__(self,*args):return False
            async def initialize(self):
                if script=='initialize_failure' and ledger['connects']<3:raise ConnectionError('P0 synthetic initialize')
            async def call_tool(self,name,arguments):
                ledger['submits']+=1
                if script=='submitted_response_lost':raise TimeoutError('P0 submitted response lost')
                status='unknown' if script=='restart_clears_ledger' else script if script in {'finished','running','unknown'} else 'finished'
                return SimpleNamespace(structuredContent={'ok':True,'job_id':'p0-job','status':status})
        with patch.object(mcp,'streamablehttp_client',lambda url:Transport()),patch.object(mcp,'ClientSession',lambda r,w:Session()),patch.object(mcp,'_CONNECT_RETRY_BASE_SECONDS',0.001):
            reply=await mcp.HarnessMcpClient('http://127.0.0.1:9/mcp').call_tool('get_task_status' if script in {'finished','running','unknown','restart_clears_ledger'} else 'grade_plugin',{'job_id':'p0-job'})
        assert ledger['submits']==1
        if script.endswith('failure'):assert ledger['connects']==3 and reply['ok']
        if script=='submitted_response_lost':assert not reply['ok'] and ledger['connects']==1
        if script=='restart_clears_ledger':assert reply['status']=='unknown'
        result('mcp-'+script,connect_attempts=ledger['connects'],submitted_calls=ledger['submits'],
               assertion='真实HarnessMcpClient有限建连重试；已提交结果未知仅1次，不盲重试')
    return results
