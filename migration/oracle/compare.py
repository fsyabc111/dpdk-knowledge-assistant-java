"""严格 JSON/HTTP/SSE 差分；黄金只能从 Python 采集，候选没有写黄金入口。"""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime
import json
import re
from typing import Any

class Difference(AssertionError):
    pass


def parse_sse(chunks) -> list[dict]:
    """增量 UTF-8 + SSE 行解析，允许任意传输分块，保留业务事件顺序。"""
    import codecs
    decoder = codecs.getincrementaldecoder('utf-8')()
    text = ''.join(decoder.decode(c if isinstance(c, bytes) else c.encode()) for c in chunks)
    text += decoder.decode(b'', final=True)
    text = text.replace('\r\n','\n').replace('\r','\n')
    result = []
    frames=text.split('\n\n')
    frames.pop()  # 冻结frontend/client.js在EOF不flush未由空行终止的尾帧。
    for frame in frames:
        if not frame.strip():
            continue
        event, data, identity = 'message', [], None
        for line in frame.split('\n'):
            if line.startswith(':'):
                continue
            field, _, value = line.partition(':')
            value = value[1:] if value.startswith(' ') else value
            if field == 'event': event = value
            elif field == 'data': data.append(value)
            elif field == 'id': identity = value
        if not data:
            continue
        try: payload = json.loads('\n'.join(data))
        except json.JSONDecodeError as exc: raise Difference('SSE data 必须是 JSON') from exc
        item = {'event':event,'data':payload}
        if identity is not None: item['id'] = identity
        result.append(item)
    return result


def validate_sse(events: list[dict]) -> None:
    if not events: raise Difference('SSE 不能为空')
    done = False
    sources = []
    identity = {}
    for index, item in enumerate(events):
        if done: raise Difference('done 后出现事件')
        event, data = item['event'], item['data']
        if not isinstance(data, dict): raise Difference('SSE payload 必须是 object')
        for key in ('session_id','request_id','task_id','message_id'):
            value = data.get(key)
            if value is not None:
                if key in identity and identity[key] != value: raise Difference(f'SSE {key} 关联漂移')
                identity[key] = value
        if event == 'sources':
            current = data.get('citations', data.get('sources', []))
            if type(current) is not list: raise Difference('sources 必须是数组')
            if current[:len(sources)] != sources: raise Difference('sources 未保留累计前缀')
            sources = current
        if event == 'done':
            status=data.get('answer_status',data.get('status'))
            terminal=data.get('terminal')
            if terminal is not None and type(terminal) is not bool: raise Difference('terminal必须是bool')
            if status=='pending' and terminal is not False: raise Difference('pending必须显式terminal:false')
            if terminal is False and status!='pending': raise Difference('非终态done必须是pending')
            if terminal is None and status not in {'answered','partial','unanswered','not_applicable','cancelled','system_error'}:
                raise Difference('常规done无terminal时必须有真实answer_status终态')
            done = True
    if not done: raise Difference('SSE 缺少 done')


@dataclass(frozen=True)
class Rule:
    pattern: str
    kind: str
    reason: str

# 仅这些字段可归一化；其他 id/时钟保持逐值比较。
RULES = [
    Rule(r'.*\.(session_id|task_id|message_id|issue_id|request_id|submission_id|assignment_id|knowledge_id|stored_message_id|stored_session_id)$','identity','运行期生成身份，保留跨响应/表/帧关联'),
    Rule(r'.*\.(sessions|messages|users|entries|items|assignments|requests|rows)\[\d+\]\.id$','identity','明确声明的实体列表主键'),
    Rule(r'.*\.body(?:\.[A-Za-z_]+(?:\[\d+\])?)*\.(id|reset_token|token)$','identity','明确实体响应主键或合成令牌'),
    Rule(r'.*\.elapsed_ms$','duration','耗时非确定性，必须保持非负整数'),
]


def normalize(value: Any, path: str = '$', *, ids=None, caller_ids=None, allow_placeholders=False) -> Any:
    if ids is None: ids = {}
    if caller_ids is None:
        caller_ids={o.get('request',{}).get('input',{}).get('request_id') for o in value.get('observations',[]) if isinstance(o.get('request',{}).get('input'),dict)} if isinstance(value,dict) else set()
    rule = next((r for r in RULES if re.fullmatch(r.pattern,path)), None)
    if rule and value is not None and not isinstance(value,(dict,list)):
        if rule.kind == 'identity':
            if isinstance(value,str) and re.fullmatch(r'<identity:\d+>',value) and not allow_placeholders: raise Difference(f'{path}: 候选不能提供归一化占位符')
            if value in caller_ids: return value
            if type(value) is not str: return value
            generated = re.fullmatch(r'(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|sess_[a-f0-9]{12}|msg_[a-f0-9]{12}|asg_[a-f0-9]{16}|grade_[a-f0-9]{16}|explain-grade_[a-f0-9]{16}|<identity:\d+>)',value)
            if path.endswith(('.token','.reset_token')):
                generated = re.fullmatch(r'[A-Za-z0-9_-]{32,}|<identity:\d+>',value)
            if not generated: return value
            if value not in ids: ids[value] = f'<identity:{len(ids)+1}>'
            return ids[value]
        if rule.kind == 'duration':
            if allow_placeholders and value=='<duration>': return value
            if type(value) is not int or value < 0: raise Difference(f'{path}: 非法耗时')
            return '<duration>'
    if isinstance(value,dict):
        return {k:normalize(value[k],f'{path}.{k}',ids=ids,caller_ids=caller_ids,allow_placeholders=allow_placeholders) for k in sorted(value)}
    if isinstance(value,list):
        return [normalize(v,f'{path}[{i}]',ids=ids,caller_ids=caller_ids,allow_placeholders=allow_placeholders) for i,v in enumerate(value)]
    return value


def exact(expected, actual, path='$'):
    if type(expected) is not type(actual): raise Difference(f'{path}: 类型不同 {type(expected).__name__}/{type(actual).__name__}')
    if isinstance(expected,dict):
        if set(expected) != set(actual): raise Difference(f'{path}: 字段/缺省不同')
        for key in expected: exact(expected[key], actual[key], f'{path}.{key}')
    elif isinstance(expected,list):
        if len(expected) != len(actual): raise Difference(f'{path}: 数组长度不同')
        for i,(a,b) in enumerate(zip(expected,actual)): exact(a,b,f'{path}[{i}]')
    elif expected != actual: raise Difference(f'{path}: 值不同')


def validate_times(value):
    if isinstance(value,dict):
        parsed={}
        for key,item in value.items():
            if key in {'created_at','updated_at','expires_at','since','last_submitted_at','verified_at'} and item not in (None,'') and not isinstance(item,dict):
                if type(item) is not str or not re.fullmatch(r'\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d{6})?\+00:00',item):
                    raise Difference(f'{key}: 必须保留UTC和0或6位微秒格式')
                parsed[key]=datetime.fromisoformat(item)
            validate_times(item)
        if 'created_at' in parsed and 'updated_at' in parsed and parsed['created_at']>parsed['updated_at']:
            raise Difference('created_at晚于updated_at')
        if 'created_at' in parsed and 'expires_at' in parsed and parsed['expires_at']<=parsed['created_at']:
            raise Difference('expires_at未晚于created_at')
    elif isinstance(value,list):
        for item in value:validate_times(item)


def compare(expected: dict, actual: dict, *, normalize_declared=True) -> None:
    for trace in (expected,actual):
        validate_times(trace)
        for record in trace.get('observations',[]):
            if record.get('media_type') == 'text/event-stream': validate_sse(record['body'])
    if normalize_declared:
        expected, actual = normalize(expected,allow_placeholders=True), normalize(actual)
    exact(expected,actual)
