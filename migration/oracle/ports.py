"""仅替换模型、固定向量和 MCP 端口；业务/权限/事务/任务逻辑全部来自冻结 backend。"""
from __future__ import annotations
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import patch
import threading
import uuid

LLM_LEDGER = []
MCP_LEDGER = []
JOBS = {}
RELEASE = 'p0-synthetic-release-1'


def fixed_vector(index=0):
    return [1.0 if i == index else 0.0 for i in range(1024)]


def grade_payload(protocol='dns'):
    return {'ok':True,'protocol':protocol,'score':80,'passed':4,'cases_total':5,
            'failed':1,'crashed':0,'timedout':0,'cheat':0,
            'results':[{'name':'synthetic-case','result':'FAIL','category':'basic',
                        'failures':[{'desc':'synthetic field','expected':'x','actual':'y'}]}]}


async def mcp_call(tool_name, arguments):
    # 账本只保存身份/计数，绝不保存 plugin_source。
    MCP_LEDGER.append({'tool':tool_name,'job_id':arguments.get('job_id')})
    if tool_name == 'list_protocols':
        return {'ok':True,'protocols':[{'name':'dns','cases':5,'categories':{'basic':5}}]}
    if tool_name == 'get_task_status':
        job_id = arguments.get('job_id')
        return {'ok':True,'job_id':job_id,**JOBS.get(job_id,{'status':'unknown'})}
    if tool_name == 'grade_plugin':
        result = grade_payload(arguments.get('protocol','dns'))
        if arguments.get('job_id'): JOBS[arguments['job_id']]={'status':'finished','result':result}
        return result
    if tool_name == 'get_reference_answer':
        return {'ok':False,'error':'synthetic_no_reference'}
    return {'ok':False,'error':'unknown_tool'}


def build_model(settings):
    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage
    class Model(FakeMessagesListChatModel):
        def bind_tools(self, tools, **kwargs):
            return self
        async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
            LLM_LEDGER.append({'tool_call_ids':[getattr(m,'tool_call_id',None) for m in messages
                                               if getattr(m,'type','')=='tool']})
            return await super()._agenerate(messages,stop,run_manager,**kwargs)
    return Model(responses=[
        AIMessage(content='',tool_calls=[{'name':'kb_search','args':{'query':'DNS'},'id':'p0-call-1'}],
                  usage_metadata={'input_tokens':20,'output_tokens':4,'total_tokens':24}),
        AIMessage(content='DNS 解析报文中的域名 [1]。',
                  usage_metadata={'input_tokens':30,'output_tokens':8,'total_tokens':38})])


class FixedEmbedding:
    async def embed(self, texts, *, usage=None, stage=''):
        return [fixed_vector() for _ in texts]


async def direct_chat(self, messages, *, usage=None, stage=''):
    LLM_LEDGER.append({'stage':stage,'tool_call_ids':[]})
    if '核验' in str(messages) or 'supported' in str(messages):
        return '{"status":"answered","supported":true}'
    return 'DNS 解析报文中的域名 [1]。'


def install_ports():
    # grade_id被拼进讲解文本；固定合法UUID端口避免随机十进制长串触发源脱敏差异。
    # 不改造grade逻辑；仍由冻结服务截取hex并完成真实事务。
    grade_counter=0
    grade_lock=threading.Lock()
    def grade_uuid4():
        nonlocal grade_counter
        with grade_lock:
            grade_counter+=1;value=grade_counter
            letters=[]
            for _ in range(8):letters.append('abcdef'[value%6]);value//=6
            prefix='abcdefab'+''.join(reversed(letters))
        return uuid.UUID(hex=prefix+'abcdefabcdefabcd',version=4)
    grade_uuid_port=SimpleNamespace(uuid4=grade_uuid4)
    patches = [
        patch('app.services.grading_service.uuid',grade_uuid_port),
        patch('app.agent.service.build_agent_chat_model',build_model),
        patch('app.agent.mcp_client.HarnessMcpClient.call_tool',lambda self,t,a:mcp_call(t,a)),
        patch('app.services.retrieval_service.build_embedding_model',lambda settings:FixedEmbedding()),
        patch('app.rag.providers.DirectOpenAIChatModel.generate',direct_chat),
    ]
    for item in patches: item.start()
    return patches
