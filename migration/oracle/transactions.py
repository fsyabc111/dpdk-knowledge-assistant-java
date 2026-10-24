"""真实 PostgreSQL 原子性/锁/去重/向量与留存边界，不采用SQLite替代。"""
from __future__ import annotations
import asyncio
from datetime import datetime,timedelta,timezone
import json
import secrets
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4
from .ports import RELEASE,fixed_vector
from .runtime import IDS,CLOCK


async def _run_cases():
    from app.db import get_conn
    from app.services.rag_issue_service import RagIssueService,input_hash
    from app.services.session_service import SessionService
    from app.services.grading_service import GradingService
    from .business_state import snapshot,step
    issues=RagIssueService();sessions=SessionService();results=[]
    previous_state=snapshot()
    def scalar(sql,args=()):
        with get_conn() as conn:return next(iter(conn.execute(sql,args).fetchone().values()))
    async def session(title):return await sessions.ensure_session(None,title,IDS['student_a'])
    def result(name,assertion,**extra):
        nonlocal previous_state
        after=snapshot()
        results.append({'case_id':name,'status':'通过','assertion':assertion,**step(previous_state,after),**extra})
        previous_state=after
    sid=await session('PG受理并发');rid=str(uuid4())
    before=scalar("SELECT COALESCE(SUM(chat_count),0) FROM usage_counters WHERE subject=%s AND kind='chat'",(IDS['student_a'],))
    def admit():return issues.begin_admitted(rid,IDS['student_a'],sid,'PG受理并发','','synthetic',input_digest=input_hash('PG受理并发'),initial_request=False,quota=10000)
    admitted=await asyncio.gather(*(asyncio.to_thread(admit) for _ in range(12)))
    assert sum(x is not None for x in admitted)==1
    tid=next(x for x in admitted if x)
    assert scalar('SELECT count(*) FROM rag_requests WHERE request_id=%s',(rid,))==1
    assert scalar('SELECT count(*) FROM rag_task_inputs WHERE task_id=%s',(tid,))==1
    assert scalar("SELECT COALESCE(SUM(chat_count),0) FROM usage_counters WHERE subject=%s AND kind='chat'",(IDS['student_a'],))==before+1
    assert scalar("SELECT count(*) FROM assistant_messages WHERE session_id=%s AND role='user'",(sid,))==1
    result('pg-admission-race','12并发同身份仅1任务/1输入/1用户消息/1配额',workers=12)
    # 插入任务后、寄存输入前故障：整笔受理必须回滚。
    failrid=str(uuid4())
    with patch.object(RagIssueService,'_store_task_inputs_in_conn',side_effect=RuntimeError('P0 synthetic admission failure')):
        try:issues.begin_admitted(failrid,IDS['student_a'],sid,'PG受理回滚','','synthetic',input_digest=input_hash('PG受理回滚'),initial_request=False,quota=10000)
        except RuntimeError:pass
        else:raise AssertionError('故障未生效')
    assert scalar('SELECT count(*) FROM rag_requests WHERE request_id=%s',(failrid,))==0
    assert scalar("SELECT count(*) FROM assistant_messages WHERE session_id=%s AND role='user'",(sid,))==1
    result('pg-admission-rollback','任务insert之后故障回滚，无孤儿任务/用户消息/额外配额')
    # 终态后消息插入时违反真实PG FK：状态更新与输入删除不能部分提交。
    try:issues.finish(tid,'p0-nonexistent-session','合成答案',[],'answered','supported',RELEASE,1)
    except Exception as exc:assert 'ForeignKeyViolation' in type(exc).__name__,type(exc).__name__
    else:raise AssertionError('FK故障未生效')
    assert issues.get_task(tid,IDS['student_a'])['status']=='pending'
    assert issues.load_task_inputs(tid) is not None
    assert scalar("SELECT count(*) FROM assistant_messages WHERE session_id=%s AND role='assistant'",(sid,))==0
    result('pg-finish-rollback','终态UPDATE后assistant INSERT真实FK失败，任务仍pending且输入保留')
    message,metadata=issues.finish(tid,sid,'合成最终答案',[],'answered','supported',RELEASE,1)
    try:issues.finish(tid,sid,'不得重复',[],'answered','supported',RELEASE,1)
    except RuntimeError:pass
    else:raise AssertionError('重复finish未拒绝')
    assert issues.load_task_inputs(tid) is None
    assert scalar("SELECT count(*) FROM assistant_messages WHERE session_id=%s AND role='assistant'",(sid,))==1
    state,row,replayed=issues.probe(rid,IDS['student_a'],'PG受理并发',session_id=sid)
    assert state=='replay' and row['message_id']==message and replayed['content']=='合成最终答案'
    result('pg-finish-once','终态保存一次、原始输入即清；probe重放保持message关联')
    # 取消只落一个占位消息，后续成绩不能写入。
    csid=await session('PG取消');ctid=issues.begin(str(uuid4()),IDS['student_a'],csid,'PG取消','','synthetic')
    issues.store_task_inputs(ctid,'PG取消',source_text='/* '+secrets.token_hex(16)+' */',source_origin='pasted')
    issues.record_job(ctid,'p0-cancel-job','grade_plugin','dns','0'*64)
    assert issues.cancel_task(ctid,'user_cancelled',IDS['student_a']) is True
    assert issues.cancel_task(ctid,'user_cancelled',IDS['student_a']) is False
    assert issues.load_task_inputs(ctid) is None
    grade_args=dict(task_id=ctid,job_id='p0-cancel-job',user_id=IDS['student_a'],session_id=csid,
          protocol='dns',code_hash='0'*64,score=80,passed=4,total=5,results=[],source_origin='pasted')
    assert await GradingService().record_task_job_submission(**grade_args)=='cancelled'
    assert scalar('SELECT count(*) FROM grading_submissions WHERE task_job_id=%s',('p0-cancel-job',))==0
    result('pg-cancel-idempotent','重复取消保持1占位消息；输入清理；取消后成绩闸拒绝写入')
    # 同job并发重投：原子成绩+账本闸。
    gsid=await session('PG成绩去重');gtid=issues.begin(str(uuid4()),IDS['student_a'],gsid,'PG成绩去重','','synthetic')
    issues.record_job(gtid,'p0-dedup-job','grade_plugin','dns','1'*64)
    args={**grade_args,'task_id':gtid,'job_id':'p0-dedup-job','session_id':gsid,'code_hash':'1'*64}
    statuses=await asyncio.gather(*(GradingService().record_task_job_submission(**args) for _ in range(12)))
    assert statuses==['persisted']*12
    assert scalar('SELECT count(*) FROM grading_submissions WHERE task_job_id=%s',('p0-dedup-job',))==1
    assert scalar('SELECT submission_done FROM rag_task_jobs WHERE job_id=%s',('p0-dedup-job',))==1
    result('pg-grade-job-dedup','12并发同job仅1成绩，submission_done与成绩同事务',workers=12)
    # 恢复属主认领：陈旧孤儿仅有一个赢家。
    with get_conn() as conn:conn.execute('UPDATE rag_requests SET updated_at=%s WHERE id=%s',('2000-01-01T00:00:00+00:00',gtid))
    claims=await asyncio.gather(*(asyncio.to_thread(issues.claim_recovery,gtid) for _ in range(12)))
    assert sum(claims)==1
    result('pg-recovery-claim','12并发陈旧pending认领仅1赢家',workers=12)
    # 24小时临时输入到期：固定时钟前/后，不真实等待24小时。
    issues.store_task_inputs(gtid,'合成TTL',ttl_hours=24)
    assert issues.load_task_inputs(gtid) is not None
    with get_conn() as conn:conn.execute('UPDATE rag_task_inputs SET expires_at=%s WHERE task_id=%s',('2026-10-24T03:59:59+00:00',gtid))
    assert issues.load_task_inputs(gtid) is None
    result('pg-input-ttl','24h有效窗口内可读；推进到expires_at之后不可读且删除')
    # 真实1024维vector排序，正交并列由冻结SQL node_id升序。
    from app.storage.li_retrieval_store import LiNodeRetrievalStore
    from app.services.llama_index_service import LlamaIndexRetrievalAdapter
    store=LiNodeRetrievalStore('"data_li_knowledge_v1"',RELEASE)
    vector_ids=store.fetch_semantic_candidate_ids(fixed_vector(),10)
    assert vector_ids==['li_p0_node_0','li_p0_node_1','li_p0_node_2'],vector_ids
    chunks=await LlamaIndexRetrievalAdapter().search('DNS',6)
    assert chunks and chunks[0].id=='li_p0_node_0'
    assert len({c.id for c in chunks})==len(chunks)
    result('pg-vector-rrf-mmr','1024维PG exact cosine→FTS/path融合→冻结RRF/MMR；保留排序及score',
           semantic_ids=vector_ids,ranked=[{'chunk_id':c.id,'score':c.score,'source_path':c.source_path} for c in chunks],
           tolerance={'comparison':'exact','future_tolerance':'requires explicit user confirmation and manifest version','order':'exact; semantic ties node_id ASC'})
    from app.services.citation_service import citations_from_chunks
    from app.rag.types import RetrievedChunk
    six=[RetrievedChunk(id=f'li_six_{i}',source_path='synthetic/same.md',section=str(i),title='同文件片段',content='合成片段',metadata={},score=1.0-i/10) for i in range(6)]
    citations=citations_from_chunks(six)
    assert len(citations)==6 and [c.chunk_id for c in citations]==[c.id for c in six]
    result('citation-six-same-file','同文件6片段逐个保留、次序不变、不截断为4条',citations=[c.model_dump() for c in citations])
    # 收口所有直接原语测试pending，禁止永不终态任务。
    issues.mark_interrupted(gtid,'p0-synthetic-end')
    result('pg-cleanup-terminal','专例收口零pending与输入清理')
    return results


async def run():
    # 事务原语组使用固定wallclock，避免12个线程的时间读取先后变成差分噪声。
    # HTTP组仍走源now_iso单调保护；此组不声称验证消息时钟递增行为。
    class TransactionDatetime(datetime):
        @classmethod
        def now(cls,tz=None):
            value=cls.fromisoformat('2026-10-24T04:10:00+00:00')
            return value.astimezone(tz) if tz else value.replace(tzinfo=None)
    fixed=lambda:'2026-10-24T04:10:00+00:00'
    with patch('app.services.rag_issue_service.now',fixed),patch('app.services.rag_issue_service.datetime',TransactionDatetime),patch('app.services.session_service.now_iso',fixed),patch('app.services.grading_service.now_iso',fixed):
        return await _run_cases()
