"""P0 合成 oracle 命令；两端独立数据库，只有Python可生成黄金。"""
from __future__ import annotations
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import sys
from .safety import BASELINE,ROOT,configure,initialize_schema


def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2,default=str)+'\n')


async def execute(database):
    identity=configure(database)
    initialize_schema()
    from .runtime import freeze_clock,seed,schema_report
    freeze_clock()
    seeds=await seed()
    from .upstream_checks import run as upstream
    upstream_results=await upstream()
    from .ports import install_ports,MCP_LEDGER,LLM_LEDGER
    patches=install_ports()
    from app.main import app
    import httpx
    from .route_cases import Cases,success,negatives
    from .transactions import run as transactions
    from app.services import task_service
    # ASGITransport不自动触发生命周期，显式schema初始化且不启后台维护/真实预热。
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://p0.test') as client:
        cases=Cases(client)
        state=await success(cases,seeds)
        routes=[r['id'] for r in json.loads((ROOT/'migration/baseline/static-inventory.json').read_text())['routes']]
        mapping=await negatives(cases,routes,state,seeds)
        from .auth_checks import run as auth_checks
        from .business_state import snapshot,step
        auth_before=snapshot()
        auth_results=await auth_checks(cases)
        auth_after=snapshot()
        auth_state_step={'case_id':'auth-vector-group',**step(auth_before,auth_after)}
    tx=await transactions()
    from .business_state import snapshot,step
    business_final=snapshot()
    from app.db import get_conn,close_database_provider
    with get_conn() as conn:
        counts={name:conn.execute(f'SELECT COUNT(*) AS n FROM {name}').fetchone()['n'] for name in [
          'users','api_tokens','assignments','assignment_items','grading_submissions','rag_requests','rag_task_inputs','rag_task_jobs',
          'assistant_sessions','assistant_messages','assistant_citations','rag_issues','rag_knowledge','rag_knowledge_revisions']}
        associations=[dict(r) for r in conn.execute("SELECT r.id AS task_id,r.request_id,r.session_id,r.message_id,r.status,r.purpose,m.id AS stored_message_id,m.session_id AS stored_session_id FROM rag_requests r LEFT JOIN assistant_messages m ON m.id=r.message_id ORDER BY r.question,r.purpose,r.user_id").fetchall()]
        marker_hits=[]
        for table,column in [('assistant_messages','content'),('rag_requests','question'),('rag_requests','context_text'),('grading_submissions','results_json')]:
            n=conn.execute(f'SELECT COUNT(*) AS n FROM {table} WHERE {column}::text LIKE %s',('%'+cases.source_marker+'%',)).fetchone()['n']
            if n:marker_hits.append({'table':table,'column':column,'count':n})
    assert not marker_hits,marker_hits
    for row in associations:
        if row['message_id'] is not None and row['stored_message_id'] is not None:
            assert row['message_id']==row['stored_message_id'] and row['session_id']==row['stored_session_id']
        elif row['message_id'] is not None:
            # 冻结delete_session留下rag_requests身份；仅本场景已显式删除的会话允许悬挂。
            assert row['task_id']==state['chat']['task_id'] and row['session_id']==state['chat']['session_id']
    assert counts['rag_task_inputs']==0 and all(row['status']!='pending' for row in associations)
    schema=schema_report()
    trace={'version':'p0-v2','baseline_id':BASELINE,'fixture_version':'p0-v2',
           'business_initial':cases.business_initial,'observations':cases.observations,
           'auth_state_step':auth_state_step,'transaction_cases':tx,
           'state':{'counts':counts,'associations':associations,'source_marker_hits':marker_hits,'business':business_final}}
    report={'identity':{k:v for k,v in identity.items() if k not in {'data_directory'}},'schema':schema,'routes':mapping,
            'upstream':upstream_results,'auth':auth_results,'transactions':tx,'privacy':{'runtime_source_marker_hits':0,'pending_tasks':0,'transient_inputs':0},
            'ledger':{'llm_calls':len(LLM_LEDGER),'mcp_calls':len(MCP_LEDGER),'mcp':MCP_LEDGER,
                      'tool_call_id_observed':any('p0-call-1' in x.get('tool_call_ids',[]) for x in LLM_LEDGER)}}
    assert report['ledger']['tool_call_id_observed']
    for item in list(task_service.TASKS.values()):
        if item.run_task and not item.run_task.done():item.run_task.cancel();await asyncio.gather(item.run_task,return_exceptions=True)
    task_service.TASKS.clear()
    close_database_provider()
    for item in reversed(patches):item.stop()
    return trace,report


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture',choices=['p0-v2'],required=True);parser.add_argument('--database',choices=['p0_python_oracle','p0_candidate_oracle'],required=True)
    args=parser.parse_args()
    trace,report=asyncio.run(execute(args.database))
    target=ROOT/'.migration-artifacts/p0'/('python-trace-p0-v2.json' if args.database=='p0_python_oracle' else 'candidate-trace-p0-v2.json')
    write(target,trace)
    write(ROOT/'docs/java-migration/evidence/J0-02/auto'/f'{args.database}-p0-v2.json',report)
    print(json.dumps({'database':args.database,'observations':len(trace['observations']),'routes':len(report['routes']),'upstream':len(report['upstream']),'pg_cases':len(report['transactions']),'tables':len(report['schema']['actual_tables'])}))

if __name__=='__main__':main()
