"""冻结 Python 的可重复合成种子；无真实数据、无旧环境依赖。"""
from __future__ import annotations
import asyncio
from datetime import datetime, timedelta, timezone
import json
from unittest.mock import patch
from .safety import ROOT
from .ports import RELEASE, fixed_vector

CLOCK = '2026-10-24T04:00:00+00:00'
IDS = {'leader':'U20270000001','teacher_a':'U20270000002','teacher_b':'U20270000003',
       'student_a':'U20270000011','student_b':'U20270000021','free':'U20270000031',
       'delete':'U20270000041','disabled':'U20270000051'}


def freeze_clock():
    import threading
    clock_lock=threading.Lock()
    logical_tick=0
    class FixedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            nonlocal logical_tick
            with clock_lock:
                value = cls.fromisoformat(CLOCK)+timedelta(microseconds=logical_tick)
                logical_tick+=1
            return value.astimezone(tz) if tz else value.replace(tzinfo=None)
    import importlib
    paths=['app.services.user_service','app.services.session_service','app.services.rag_index_service',
           'app.services.rag_issue_service','app.services.grading_service','app.services.assignment_service',
           'app.core.rate_limit','app.api.usage','app.api.assignments']
    patches=[]
    for path in paths:
        module=importlib.import_module(path)
        if hasattr(module,'datetime'):
            item=patch.object(module,'datetime',FixedDatetime);item.start();patches.append(item)
    return patches


def prepare_node_table():
    # 使用冻结库自己的 PGVectorStore 建物理表，不复制第二份业务DDL。
    from app.config import get_settings
    from app.services.llama_index_service import _new_pgvector_store
    store=_new_pgvector_store(get_settings(),perform_setup=True)
    store._initialize()
    if getattr(store,'_engine',None):store._engine.dispose()
    return store


async def seed():
    from app.db import get_conn
    from app.services.user_service import UserService
    from app.services.session_service import SessionService
    from app.services.rag_issue_service import RagIssueService
    from app.services.rag_index_service import index_configuration
    from app.config import get_settings
    prepare_node_table()
    with get_conn() as conn:
        tables=conn.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'alembic_version'").fetchall()
        names=','.join('"'+row['tablename'].replace('"','""')+'"' for row in tables)
        conn.execute(f'TRUNCATE {names} CASCADE')
    users=UserService()
    await users.create_users([IDS['leader']],roles=['leader'],daily_quota=10000)
    await users.create_users([IDS['teacher_a'],IDS['teacher_b']],roles=['admin'],daily_quota=10000,created_by=IDS['leader'])
    await users.create_users([IDS['student_a'],IDS['delete'],IDS['disabled']],created_by=IDS['teacher_a'],daily_quota=10000)
    await users.create_users([IDS['student_b']],created_by=IDS['teacher_b'],daily_quota=10000)
    await users.create_users([IDS['free']],daily_quota=10000)
    vector=lambda i:'['+','.join(str(x) for x in fixed_vector(i))+']'
    with get_conn() as conn:
        conn.execute("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,3)",
                     (RELEASE,index_configuration(get_settings()),CLOCK))
        conn.execute("INSERT INTO rag_index_state(name,release_id) VALUES ('active',%s)",(RELEASE,))
        for i in range(3):
            node=f'li_p0_node_{i}'
            conn.execute('INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)',
              (node,'DNS 解析报文中的域名',json.dumps({'source_path':f'synthetic/dns{i//2}.md','title':'DNS','section':'parse'}),vector(i)))
            conn.execute('INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)',(RELEASE,node))
        conn.execute('INSERT INTO knowledge_documents(id,source_path,source_type,title,content_hash,indexed_at) VALUES (%s,%s,%s,%s,%s,%s)',
                     ('p0-doc','synthetic/history.md','markdown','历史DNS','0'*64,CLOCK))
        conn.execute('INSERT INTO knowledge_chunks(id,document_id,source_path,section,content,metadata_json) VALUES (%s,%s,%s,%s,%s,%s::jsonb)',
                     ('p0-history','p0-doc','synthetic/history.md','历史','历史固定来源','{}'))
    sessions=SessionService(); issues=RagIssueService()
    session=await sessions.ensure_session(None,'合成历史',IDS['student_a'])
    history_message=await sessions.add_message(session,'assistant','历史固定回答',{'confidence':'medium'})
    await sessions.add_citation(history_message,'p0-history',1.0,1)
    failed_session=await sessions.ensure_session(None,'合成未解答',IDS['student_a'])
    task=issues.begin('p0-seed-failed',IDS['student_a'],failed_session,'合成未解答问题','','synthetic')
    message,_=issues.finish(task,failed_session,'资料不足',[],'unanswered','no_evidence',RELEASE,1)
    return {'history_session':session,'history_message':history_message,'failed_task':task,'failed_message':message}


def schema_report():
    from app.db import get_conn
    inventory=json.loads((ROOT/'migration/baseline/static-inventory.json').read_text())
    expected={t['name'].replace('public.','').replace('{llama_index_table}','li_knowledge_v1') for t in inventory['production_tables']}
    with get_conn() as conn:
        tables={r['tablename'] for r in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname='public'").fetchall()}
        indexes=[dict(r) for r in conn.execute("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname").fetchall()]
        constraints=[dict(r) for r in conn.execute("SELECT c.conname, c.contype, c.conrelid::regclass::text AS table_name,pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public' ORDER BY table_name,c.conname").fetchall()]
        head=[r['version_num'] for r in conn.execute('SELECT version_num FROM alembic_version').fetchall()]
    assert expected<=tables, sorted(expected-tables)
    names={x['indexname'] for x in indexes}
    assert {'idx_grading_task_job_id','idx_rag_request_session_key','idx_rag_request_initial_key'}<=names,names
    return {'expected_tables':sorted(expected),'actual_tables':sorted(tables),'missing':sorted(expected-tables),
            'alembic_head':head,'indexes':indexes,'constraints':constraints}
