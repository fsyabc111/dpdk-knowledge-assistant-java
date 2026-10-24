"""严格比较业务字段与冻结 React 可消费的 SSE；候选没有写黄金入口。"""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime
import json
import math
import re
from typing import Any


class Difference(AssertionError):
    """结构合法，但候选违反业务契约或与黄金不同（CLI退出1）。"""


class InvalidTrace(ValueError):
    """JSON/trace容器结构、资源上限或日历值损坏（CLI退出2）。"""


TERMINAL_STATUSES = {'answered', 'partial', 'unanswered', 'not_applicable', 'cancelled', 'system_error'}
TIME_FIELDS = {'created_at','updated_at','expires_at','since','last_submitted_at','verified_at',
               'issued_at','revoked_at','reset_expires_at','decided_at','accepted_at','assignment_updated_at','indexed_at'}
PLACEHOLDER = re.compile(r'<(?:identity:\d+|id:[a-z_]+:\d+)>')
HEX32 = r'[a-f0-9]{32}'
UUID = r'[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}'
ID_FORMATS = {
    'task': HEX32, 'message': rf'(?:{HEX32}|msg_[a-f0-9]{{12}})',
    'session': r'(?:sess_[a-f0-9]{12}|explain-grade_[a-f0-9]{16})',
    'assignment': r'asg_[a-f0-9]{16}', 'grade': r'grade_[a-f0-9]{16}',
    'knowledge': HEX32, 'revision': HEX32, 'reset_request': HEX32,
    'citation': rf'(?:{HEX32}|cit_[a-f0-9]{{12}})', 'issue_event': HEX32, 'ledger': HEX32,
    'assignment_item': r'item_[a-f0-9]{16}', 'audit': r'aud_[a-f0-9]{12}',
    'usage': r'use_[a-f0-9]{12}', 'request': UUID,
    'token': r'[A-Za-z0-9_-]{32,}',
}
FIELD_KINDS = {
    'task_id':'task', 'originating_task_id':'task', 'issue_id':'task',
    'message_id':'message', 'stored_message_id':'message',
    'session_id':'session', 'stored_session_id':'session',
    'assignment_id':'assignment', 'submission_id':'grade', 'knowledge_id':'knowledge',
    'revision_id':'revision', 'reset_request_id':'reset_request', 'citation_id':'citation',
    'merged_issue_id':'task','summary_message_id':'message','issue_event_id':'issue_event', 'ledger_id':'ledger', 'assignment_item_id':'assignment_item',
    'audit_id':'audit', 'usage_id':'usage', 'token':'token', 'reset_token':'token',
}


def validate_trace_schema(trace: Any) -> None:
    """先迭代验证容器/资源上限，避免递归或损坏输入产生traceback。"""
    if type(trace) is not dict: raise InvalidTrace('trace顶层必须是object')
    if 'state' in trace and type(trace['state']) is not dict:raise InvalidTrace('state必须是object')
    observations=trace.get('observations')
    if type(observations) is not list: raise InvalidTrace('observations必须是array')
    stack=[(trace,0)]; nodes=0
    while stack:
        value,depth=stack.pop();nodes+=1
        if depth>64 or nodes>2_000_000:raise InvalidTrace('trace嵌套或节点数量超过限制')
        if isinstance(value,dict):
            if any(type(k) is not str for k in value):raise InvalidTrace('object key必须是string')
            stack.extend((v,depth+1) for v in value.values())
        elif isinstance(value,list):stack.extend((v,depth+1) for v in value)
        elif type(value) not in {str,int,float,bool,type(None)}:raise InvalidTrace('不支持的JSON值')
        elif isinstance(value,float) and not math.isfinite(value):raise InvalidTrace('JSON数值必须有限')
        elif isinstance(value,str) and len(value)>16_000_000:raise InvalidTrace('JSON字符串超过限制')
    for record in observations:
        if type(record) is not dict:raise InvalidTrace('observation必须是object')
        if type(record.get('status')) is not int or not 100<=record['status']<=599:raise InvalidTrace('observation.status必须是HTTP整数')
        if type(record.get('media_type')) is not str or 'body' not in record:raise InvalidTrace('observation缺media_type/body')
        if 'route_id' in record and type(record['route_id']) is not str:raise InvalidTrace('route_id必须是string')
        if 'request' in record:
            request=record['request']
            if type(request) is not dict:raise InvalidTrace('observation.request必须是object')
            for name in ('input','params'):
                if name in request and request[name] is not None and type(request[name]) is not dict:raise InvalidTrace('request.'+name+'必须是object或NULL')
            if 'path_parameters' in request and type(request['path_parameters']) is not dict:raise InvalidTrace('request.path_parameters必须是object')
        if record['media_type']=='text/event-stream':
            if type(record['body']) is not list:raise InvalidTrace('SSE body必须是array')
            for event in record['body']:
                if type(event) is not dict or type(event.get('event')) is not str or type(event.get('data')) is not dict:
                    raise InvalidTrace('SSE event/data容器损坏')
        if trace.get('fixture_version')=='p0-v2':
            if type(record.get('business_before')) is not dict or type(record.get('business_after')) is not dict:
                raise InvalidTrace('p0-v2每个observation必须有业务前后状态')
    if trace.get('fixture_version')=='p0-v2':
        if type(trace.get('state')) is not dict or type(trace['state'].get('business')) is not dict:raise InvalidTrace('p0-v2缺最终业务状态object')
        if type(trace.get('transaction_cases')) is not list or any(type(item) is not dict for item in trace['transaction_cases']):raise InvalidTrace('transaction_cases必须是object数组')
        if type(trace.get('auth_state_step')) is not dict:raise InvalidTrace('auth_state_step必须是object')
        if type(trace.get('business_initial')) is not dict:raise InvalidTrace('business_initial必须是object')


def parse_sse(chunks) -> list[dict]:
    """匹配冻结client.js：LF空行分帧、取首个event/data行、EOF不flush尾帧。"""
    import codecs
    decoder=codecs.getincrementaldecoder('utf-8')()
    try:
        wire=''.join(decoder.decode(c if isinstance(c,bytes) else c.encode()) for c in chunks)
        wire+=decoder.decode(b'',final=True)
    except (UnicodeError,TypeError,AttributeError) as exc:raise Difference('SSE UTF8线格式损坏') from exc
    if '\r' in wire:raise Difference('冻结React不消费CRLF/CR帧；必须LF线格式')
    result=[]
    for frame in wire.split('\n\n')[:-1]:
        event=re.search(r'^event:\s*(.+)$',frame,re.MULTILINE)
        data=re.search(r'^data:\s*(.+)$',frame,re.MULTILINE)
        if event is None or data is None:continue
        try:payload=json.loads(data.group(1))
        except (ValueError,RecursionError) as exc:raise Difference('SSE data必须是合法JSON') from exc
        result.append({'event':event.group(1),'data':payload})
    return result


def validate_sse(events: list[dict]) -> str:
    if not events:raise Difference('SSE不能为空')
    ended=None;sources=[];identity={}
    for item in events:
        if ended:raise Difference('SSE结束帧后出现事件')
        event,data=item['event'],item['data']
        if type(data) is not dict:raise InvalidTrace('SSE payload必须是object')
        for key in ('session_id','request_id','task_id','message_id'):
            value=data.get(key)
            if value is not None:
                if key in identity and identity[key]!=value:raise Difference(f'SSE {key}关联漂移')
                identity[key]=value
        if event=='sources':
            current=data.get('citations',data.get('sources',[]))
            if type(current) is not list:raise Difference('sources必须是数组')
            if current[:len(sources)]!=sources:raise Difference('sources未保留累计前缀')
            sources=current
        elif event=='error':
            if type(data.get('message')) is not str or not data['message'] or type(data.get('retryable')) is not bool:
                raise Difference('error结束帧必须含message和bool retryable')
            ended='error'
        elif event=='done':
            status=data.get('answer_status',data.get('status'));terminal=data.get('terminal')
            if terminal is not None and type(terminal) is not bool:raise Difference('terminal必须是bool')
            if status=='pending':
                if terminal is not False:raise Difference('pending必须显式terminal:false')
                ended='pending'
            else:
                if status not in TERMINAL_STATUSES or terminal is False:raise Difference('done必须是真实终态或pending:false')
                ended='done'
    if ended is None:raise Difference('SSE缺少done或error结束帧')
    return ended


@dataclass(frozen=True)
class Rule:
    pattern:str
    kind:str
    reason:str


RULES=[Rule(r'.*\.(?:'+ '|'.join(FIELD_KINDS)+r'|request_id|id)$','typed_identity','字段/实体命名校验后按全trace双射'),
       Rule(r'.*\.(elapsed_ms|duration_ms)$','duration','运行耗时非确定，保留非负整数或NULL类型')]


def _kind(path,value,route):
    key=path.rsplit('.',1)[-1]
    if key in FIELD_KINDS:return FIELD_KINDS[key]
    if key=='request_id':
        if '/api/rag/issues' in route and '.body.' in path:return 'task'
        if '/api/admin/password-reset-requests/' in route and '.request.path_parameters.' in path:return 'reset_request'
        return 'request'
    if key!='id':return None
    if '.messages[' in path:return 'message'
    if '.sessions[' in path:return 'session'
    if '.users[' in path:return None
    if '.requests[' in path or '/api/admin/password-reset-requests' in route:return 'reset_request'
    if '/api/assignments' in route:return 'assignment'
    if '/api/grading/submissions' in route:return 'grade'
    if '/api/chat/sessions' in route:return 'session'
    if '/api/rag/knowledge' in route:return 'knowledge'
    if '/api/rag/issues' in route:return 'task'
    if '/api/admin/audit-log' in route:return 'audit'
    # 没有端点上下文的纯内存样例不按通用格式并集猜实体。
    return None


def normalize(value:Any,path='$',*,ids=None,caller_ids=None,allow_placeholders=False,route='',identity_kinds=None):
    if ids is None:ids={}
    if identity_kinds is None:identity_kinds={}
    if caller_ids is None:
        caller_ids={o['request']['input']['request_id'] for o in value.get('observations',[])
                    if isinstance(o.get('request',{}).get('input'),dict) and isinstance(o['request']['input'].get('request_id'),str)} if isinstance(value,dict) else set()
    if isinstance(value,dict) and 'route_id' in value:route=value['route_id']
    key=path.rsplit('.',1)[-1]
    if not isinstance(value,(dict,list)) and value is not None:
        if key in {'code_hash','code_sha256','input_hash'}:
            if type(value) is not str or re.fullmatch(r'[a-f0-9]{64}',value) is None:raise Difference(f'{path}: 非法sha256身份')
            return value
        if key in {'elapsed_ms','duration_ms'}:
            if allow_placeholders and value=='<duration>':return value
            if type(value) is not int or value<0:raise Difference(f'{path}: 非法耗时')
            return '<duration>'
        kind=_kind(path,value,route)
        if kind is not None:
            if value in caller_ids:return value
            if type(value) is not str:raise Difference(f'{path}: 身份必须是string')
            placeholder=PLACEHOLDER.fullmatch(value)
            if placeholder and not allow_placeholders:raise Difference(f'{path}: 候选不能提供占位符')
            # 缺失资源/无效输入以及固定的种子request_id保持逐值比较。
            if '.request.' in path and value in {'p0-missing','','<synthetic-credential>'}:return value
            if key=='request_id' and value=='p0-seed-failed':return value
            if not placeholder and re.fullmatch(ID_FORMATS[kind],value) is None:raise Difference(f'{path}: {kind}身份格式不符')
            previous=identity_kinds.setdefault(value,kind)
            if previous!=kind:raise Difference(f'{path}: 同一身份跨实体类型引用')
            if value not in ids:ids[value]=f'<identity:{len(ids)+1}>'
            return ids[value]
    kwargs={'ids':ids,'caller_ids':caller_ids,'allow_placeholders':allow_placeholders,'route':route,'identity_kinds':identity_kinds}
    if isinstance(value,dict):return {k:normalize(value[k],f'{path}.{k}',**kwargs) for k in sorted(value)}
    if isinstance(value,list):return [normalize(v,f'{path}[{i}]',**kwargs) for i,v in enumerate(value)]
    return value


FLOAT_TOLERANCE_RULES=[{'pattern':r'\$\.transaction_cases\[\d+\]\.ranked\[\d+\]\.score','absolute':1e-12,'reason':'U09 用户批准raw检索分数容差；顺序/citation.round4/业务分数仍exact'}]


def exact(expected,actual,path='$'):
    if type(expected) is float and type(actual) is float:
        rule=next((r for r in FLOAT_TOLERANCE_RULES if re.fullmatch(r['pattern'],path)),None)
        if rule is not None:
            if abs(expected-actual)>rule['absolute']:raise Difference(f'{path}: raw检索分数超过绝对容差')
            return
    if type(expected) is not type(actual):raise Difference(f'{path}: 类型不同 {type(expected).__name__}/{type(actual).__name__}')
    if isinstance(expected,dict):
        if set(expected)!=set(actual):raise Difference(f'{path}: 字段/缺省不同')
        for key in expected:exact(expected[key],actual[key],f'{path}.{key}')
    elif isinstance(expected,list):
        if len(expected)!=len(actual):raise Difference(f'{path}: 数组长度不同')
        for index,(a,b) in enumerate(zip(expected,actual)):exact(a,b,f'{path}[{index}]')
    elif expected!=actual:raise Difference(f'{path}: 值不同')


def validate_times(value):
    if isinstance(value,dict):
        parsed={}
        for key,item in value.items():
            if key in TIME_FIELDS and item not in (None,'') and not isinstance(item,dict):
                if type(item) is not str or not re.fullmatch(r'\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d{6})?\+00:00',item):
                    raise Difference(f'{key}: 必须保留UTC和0或6位微秒格式')
                try:parsed[key]=datetime.fromisoformat(item)
                except ValueError as exc:raise InvalidTrace(f'{key}: 非法日历值') from exc
            validate_times(item)
        if 'created_at' in parsed and 'updated_at' in parsed and parsed['created_at']>parsed['updated_at']:raise Difference('created_at晚于updated_at')
        if 'created_at' in parsed and 'expires_at' in parsed and parsed['expires_at']<=parsed['created_at']:raise Difference('expires_at未晚于created_at')
    elif isinstance(value,list):
        for item in value:validate_times(item)



def validate_business_chain(trace):
    """用白名单初态和逐case真实行增删重建前置状态；不依赖DB摘要hash。"""
    if trace.get('fixture_version')!='p0-v2':return
    from collections import Counter
    initial=trace.get('business_initial',{})
    tables=initial.get('tables') if isinstance(initial,dict) else None
    if type(tables) is not dict:raise InvalidTrace('p0-v2缺白名单业务初态')
    def rows_valid(value):
        return type(value) is list and all(type(row) is dict for row in value)
    if not all(rows_valid(rows) for rows in tables.values()):raise InvalidTrace('业务表必须是object行数组')
    current={table:list(rows) for table,rows in tables.items()}
    steps=[*trace['observations'],trace.get('auth_state_step'),*trace.get('transaction_cases',[])]
    for item in steps:
        if type(item) is not dict:raise InvalidTrace('业务checkpoint缺失')
        before=item.get('business_before');after=item.get('business_after');delta=item.get('business_delta')
        if not all(type(v) is dict for v in (before,after,delta)) or type(delta.get('tables')) is not dict:
            raise InvalidTrace('业务checkpoint/delta容器损坏')
        if set(delta['tables'])!=set(current):raise InvalidTrace('业务delta必须逐表声明，未变化表也不得省略')
        if type(before.get('row_counts')) is not dict or type(after.get('row_counts')) is not dict:raise InvalidTrace('业务checkpoint.row_counts必须是object')
        if before.get('row_counts')!={table:len(rows) for table,rows in current.items()}:raise Difference('业务前置行数与上一checkpoint不符')
        for table,change in delta['tables'].items():
            if type(change) is not dict or not rows_valid(change.get('removed')) or not rows_valid(change.get('added')):
                raise InvalidTrace('业务delta必须含removed/added行数组')
            for row in change['removed']:
                if row not in current[table]:raise Difference('业务delta删除了不属于前置状态的行')
                current[table].remove(row)
            current[table].extend(change['added'])
        if after.get('row_counts')!={table:len(rows) for table,rows in current.items()}:raise Difference('业务后置行数与实际delta不符')
    final=trace['state']['business'].get('tables')
    if type(final) is not dict or set(final)!=set(current) or not all(rows_valid(rows) for rows in final.values()):raise InvalidTrace('最终业务表结构损坏')
    key=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False,separators=(',',':'))
    for table,rows in current.items():
        if Counter(map(key,rows))!=Counter(map(key,final[table])):raise Difference('逐case业务delta无法重建最终业务状态: '+table)

def compare(expected:dict,actual:dict,*,normalize_declared=True)->None:
    for trace in (expected,actual):
        validate_trace_schema(trace);validate_times(trace);validate_business_chain(trace)
        for record in trace['observations']:
            if record['media_type']=='text/event-stream':validate_sse(record['body'])
    if normalize_declared:expected,actual=normalize(expected,allow_placeholders=True),normalize(actual)
    exact(expected,actual)
