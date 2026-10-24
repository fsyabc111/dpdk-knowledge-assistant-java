"""白名单业务投影；不读出密码/令牌摘要、源码原文或上游原始输出。"""
from __future__ import annotations
from datetime import datetime
import json
import hashlib
import re

# 每个SELECT显式枚举列；不以表hash或行数代替业务值。
QUERIES={
 'users':"SELECT id AS user_id,roles,daily_quota,enabled,must_change_password,created_by,created_at FROM users ORDER BY id",
 'api_tokens':"SELECT user_id,issued_at,expires_at,revoked_at FROM api_tokens ORDER BY user_id,issued_at,expires_at",
 'password_reset_requests':"SELECT id AS reset_request_id,user_id,status,(reset_token_hash IS NOT NULL) AS has_reset_token,reset_expires_at,created_at,decided_at,decided_by FROM password_reset_requests ORDER BY created_at,user_id,id",
 'admin_audit_log':"SELECT id AS audit_id,actor,action,targets_json AS targets,created_at FROM admin_audit_log ORDER BY created_at,actor,action,id",
 'assistant_sessions':"SELECT id AS session_id,user_id,title,rag_release_id AS release_id,(memory_summary<>'') AS has_memory_summary,memory_summary_through_message_id AS summary_message_id,created_at,updated_at FROM assistant_sessions ORDER BY title,user_id,created_at,id",
 'assistant_messages':"SELECT m.id AS message_id,m.session_id,m.role,m.content AS _content_source,octet_length(m.content) AS content_bytes,m.metadata_json AS metadata,m.created_at FROM assistant_messages m JOIN assistant_sessions s ON s.id=m.session_id ORDER BY s.title,m.created_at,m.role,md5(m.content),m.id",
 'assistant_citations':"SELECT c.id AS citation_id,c.message_id,c.chunk_id,c.citation_engine,c.score,c.rank FROM assistant_citations c JOIN assistant_messages m ON m.id=c.message_id JOIN assistant_sessions s ON s.id=m.session_id ORDER BY s.title,m.created_at,c.rank,c.id",
 'usage_counters':"SELECT subject,day,kind,chat_count FROM usage_counters ORDER BY subject,day,kind",
 'upstream_usage_records':"SELECT id AS usage_id,request_id,subject,session_id,kind,stage,model,prompt_tokens,completion_tokens,total_tokens,usage_source,call_count,duration_ms,cost_micros,currency,price_version,created_at FROM upstream_usage_records ORDER BY created_at,subject,stage,kind,model,usage_source,prompt_tokens,completion_tokens,total_tokens,call_count,cost_micros,currency,id",
 'assignments':"SELECT id AS assignment_id,title,description,created_by,scope,deadline,status,created_at,updated_at FROM assignments ORDER BY created_at,title,id",
 'assignment_items':"SELECT i.id AS assignment_item_id,i.assignment_id,i.protocol,i.points FROM assignment_items i JOIN assignments a ON a.id=i.assignment_id ORDER BY a.created_at,a.title,i.protocol,i.id",
 'grading_submissions':"SELECT id AS submission_id,user_id,session_id,protocol,code_hash,score,passed,total,results_json AS grade_details,assignment_id,task_job_id AS job_id,created_at FROM grading_submissions ORDER BY created_at,user_id,protocol,id",
 'rag_requests':"SELECT id AS task_id,request_id,input_hash,initial_request,purpose,user_id,session_id,message_id,question AS _question_source,context_text AS _context_source,status,reason,release_id,evidence_json AS evidence,model_version,prompt_version,elapsed_ms,created_at,updated_at FROM rag_requests ORDER BY question,purpose,user_id,created_at,id",
 'rag_task_inputs':"SELECT task_id,octet_length(question) AS question_bytes,attachment_name,attachment_suffix,(source_text IS NOT NULL) AS has_source,octet_length(source_text) AS source_bytes,source_origin,created_at,expires_at FROM rag_task_inputs ORDER BY created_at,task_id",
 'rag_task_jobs':"SELECT id AS ledger_id,task_id,job_id,tool,protocol,code_hash,status,result_json AS job_result,submission_done,created_at,updated_at FROM rag_task_jobs ORDER BY tool,protocol,created_at,job_id",
 'rag_issues':"SELECT i.id AS issue_id,i.request_id AS originating_task_id,i.status,i.owner_id,i.note,i.feedback,i.merged_into AS merged_issue_id,i.knowledge_id,i.verified_release_id,i.verified_at,i.updated_at FROM rag_issues i JOIN rag_requests r ON r.id=i.request_id ORDER BY r.question,r.user_id,i.id",
 'rag_issue_events':"SELECT e.id AS issue_event_id,e.issue_id,e.actor_id,e.action,e.detail,e.created_at FROM rag_issue_events e JOIN rag_requests r ON r.id=e.issue_id ORDER BY r.question,e.created_at,e.action,e.id",
 'rag_knowledge':"SELECT id AS knowledge_id,created_by,title,body,revision,status,updated_at FROM rag_knowledge ORDER BY title,revision,id",
 'rag_knowledge_revisions':"SELECT id AS revision_id,knowledge_id,revision,actor_id,title,body,status,created_at FROM rag_knowledge_revisions ORDER BY title,revision,created_at,id",
 'rag_releases':"SELECT id AS release_id,configuration,created_at,node_count FROM rag_releases ORDER BY created_at,id",
 'rag_index_state':"SELECT name,release_id FROM rag_index_state ORDER BY name",
 'rag_release_nodes':"SELECT release_id,node_id FROM rag_release_nodes ORDER BY release_id,node_id",
 'knowledge_documents':"SELECT id AS document_id,source_path,source_type,title,content_hash,git_commit,indexed_at FROM knowledge_documents ORDER BY source_path,id",
 'knowledge_chunks':"SELECT id AS chunk_id,document_id,source_path,section,md5(content) AS content_fingerprint,octet_length(content) AS content_bytes,metadata_json AS metadata FROM knowledge_chunks ORDER BY source_path,section,id",
 'li_nodes':"SELECT node_id,md5(text) AS content_fingerprint,octet_length(text) AS content_bytes,metadata_ AS metadata,vector_dims(embedding) AS embedding_dimensions FROM data_li_knowledge_v1 ORDER BY node_id",
}
SAFE_MESSAGE_METADATA={'request_id','task_id','stream_status','confidence','answer_status','answer_reason','issue_id'}
JSON_FIELDS={'roles','targets','metadata','grade_details','evidence','job_result'}


def _json(value):
    if isinstance(value,str):return json.loads(value)
    return value


def _result(result):
    """只保留评测事实；不导出源码、自由诊断全文或未知上游键。"""
    if result is None:return None
    value=_json(result)
    if not isinstance(value,dict):return {'payload_type':type(value).__name__}
    allowed={'ok','error','protocol','score','passed','cases_total','total','failed','crashed','timedout','cheat','status'}
    out={key:value[key] for key in sorted(allowed&set(value))}
    if 'results' in value:
        out['results']=[{k:item[k] for k in ('name','result','passed','category') if k in item}
                        | {'failure_count':len(item.get('failures',[]))} for item in value['results'] if isinstance(item,dict)]
    return out


def text_identity_projection(text,known_entities):
    # 只分离本次实际业务表已经登记的实体；疑似ID字面示例仍属于正文指纹。
    links=[]
    if not known_entities:return hashlib.sha256(str(text or '').encode()).hexdigest(),links
    pattern=r'(?<![A-Za-z0-9_])(?:'+'|'.join(re.escape(value) for value in sorted(known_entities,key=lambda x:(-len(x),x)))+r')(?![A-Za-z0-9_])'
    def substitute(match):
        value=match.group(0);field=known_entities[value]
        links.append({field:value})
        return '<'+field+'>'
    literal=re.sub(pattern,substitute,str(text or ''))
    return hashlib.sha256(literal.encode()).hexdigest(),links


def project_row(table,row,known_entities=None):
    known_entities=known_entities or {}
    row=dict(row)
    for key,value in list(row.items()):
        if isinstance(value,datetime):row[key]=value.isoformat()
        elif key in JSON_FIELDS and value is not None:row[key]=_json(value)
    if table=='assistant_messages':
        row['metadata']={k:v for k,v in row['metadata'].items() if k in SAFE_MESSAGE_METADATA}
        row['content_fingerprint'],row['content_identity_links']=text_identity_projection(row.pop('_content_source',''),known_entities)
    if table=='rag_requests':
        row['question_fingerprint'],row['question_identity_links']=text_identity_projection(row.pop('_question_source'),known_entities)
        row['context_fingerprint'],row['context_identity_links']=text_identity_projection(row.pop('_context_source'),known_entities)
    if table=='grading_submissions':
        details=row['grade_details'] or {}
        row['grade_details']={'source_origin':details.get('source_origin'),
                              'results':_result({'results':details.get('results',[])})['results']}
        if 'accepted_assignment' in details:
            accepted=details['accepted_assignment']
            row['grade_details']['accepted_assignment']={k:accepted[k] for k in ('assignment_id','title','created_by','scope','deadline','status','points','accepted_at','assignment_updated_at') if k in accepted}
    if table in {'knowledge_chunks','li_nodes'}:
        row['metadata']={k:v for k,v in row['metadata'].items() if k in {'source_path','section','title','source_key','ordinal','knowledge_id','revision'}}
    if table=='rag_task_jobs':row['job_result']=_result(row['job_result'])
    return row


def snapshot():
    """单个只读repeatable-read事务内取得白名单业务值；导入本模块不连接PG。"""
    from app.db import get_conn
    raw_tables={}
    with get_conn() as conn:
        conn.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
        for table,query in QUERIES.items():raw_tables[table]=[dict(row) for row in conn.execute(query).fetchall()]
    aliases={'task_id','session_id','message_id','assignment_id','submission_id','knowledge_id','revision_id','citation_id','ledger_id','issue_event_id','assignment_item_id','audit_id','usage_id','reset_request_id'}
    known_entities={row[key]:key for rows in raw_tables.values() for row in rows for key in aliases if isinstance(row.get(key),str) and row[key]}
    tables={table:[project_row(table,row,known_entities) for row in rows] for table,rows in raw_tables.items()}
    return {'projection_version':'business-v2','tables':tables}


def changes(before,after):
    """完整前后值在trace；本摘要只辅助定位，不能替代逐字段比较。"""
    return {'table_row_counts':{table:{'before':len(before['tables'][table]),'after':len(after['tables'][table])}
             for table in before['tables'] if before['tables'][table]!=after['tables'][table]}}


def counts(state):
    return {table:len(rows) for table,rows in state['tables'].items()}


def delta(before,after):
    """按行值多重集导出实际before/after，不省略未变化表；数组保留查询顺序。"""
    from collections import Counter
    key=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False,separators=(',',':'))
    tables={}
    for table in before['tables']:
        old,new=before['tables'][table],after['tables'][table]
        old_counts,new_counts=Counter(map(key,old)),Counter(map(key,new))
        removing=old_counts-new_counts;adding=new_counts-old_counts
        removed=[];added=[]
        for row in old:
            label=key(row)
            if removing[label]:removed.append(row);removing[label]-=1
        for row in new:
            label=key(row)
            if adding[label]:added.append(row);adding[label]-=1
        tables[table]={'removed':removed,'added':added}
    return {'tables':tables}


def step(before,after):
    return {'business_before':{'row_counts':counts(before)},'business_delta':delta(before,after),
            'business_after':{'row_counts':counts(after)}}
