package io.dpdk.assistant.acceptance.contract;

import io.dpdk.assistant.contracts.grading.*;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.contracts.task.*;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;

/** J5 PG专例：全部调用生产事务/任务/成绩/检索实现；只在本轮owned库运行。 */
final class TransactionCases {
    private final DataSource ds;
    private final TransactionRunner tx;
    private final SessionService sessions;
    private final RagTaskService tasks;
    private final TaskStore store;
    private final TaskGradeLedger grades;
    private final PgReleaseReader retrieval;
    private final String user="U20270000011",release="p0-synthetic-release-1";
    private String admissionSession,admissionRequest,admissionTask,cancelTask,cancelSession,gradeTask,gradeSession;
    TransactionCases(ConfigurableApplicationContext ctx) {
        ds=ctx.getBean(DataSource.class);tx=ctx.getBean(TransactionRunner.class);sessions=ctx.getBean(SessionService.class);
        tasks=ctx.getBean(RagTaskService.class);store=ctx.getBean(TaskStore.class);grades=ctx.getBean(TaskGradeLedger.class);retrieval=ctx.getBean(PgReleaseReader.class);
    }
    private void require(boolean value,String what){if(!value)throw new IllegalStateException("专例断言失败: "+what);}
    private String session(String title){return sessions.ensureOwned(Optional.empty(),title,user).value();}
    private long scalar(String sql,Object...args)throws Exception {
        try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    private void sql(String text,Object...args)throws Exception {
        try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement(text)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}
    }
    private <T> List<T> parallel(Callable<T> body)throws Exception {
        try(var workers=Executors.newFixedThreadPool(12)){
            CountDownLatch ready=new CountDownLatch(12),go=new CountDownLatch(1);List<Future<T>> fs=new ArrayList<>();
            for(int i=0;i<12;i++)fs.add(workers.submit(()->{ready.countDown();go.await();return body.call();}));
            require(ready.await(5,TimeUnit.SECONDS),"12 workers barrier");go.countDown();List<T> out=new ArrayList<>();for(var f:fs)out.add(f.get(20,TimeUnit.SECONDS));return out;
        }
    }
    private AdmissionInput input(String request,String sid,String question){return new AdmissionInput(new RequestIdentity(request,TaskInputHash.compute(question),false,RequestIdentity.Purpose.chat),user,sid,question,"","synthetic",question,10000);}
    private String begin(String sid,String question){
        String id=UUID.randomUUID().toString().replace("-","");
        tx.required(t->{try{require(store.insertRequest(t,id,UUID.randomUUID().toString(),TaskInputHash.compute(question),false,"chat",user,sid,question,"","synthetic"),"begin insert");return null;}catch(SQLException e){throw new IllegalStateException(e);}});return id;
    }
    Map<String,Object> run(String name)throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();out.put("case_id",name);
        switch(name){
            case "pg-admission-race" -> {
                admissionSession=session("PG受理并发");admissionRequest=UUID.randomUUID().toString();long before=scalar("SELECT COALESCE(SUM(chat_count),0) FROM usage_counters WHERE subject=? AND kind='chat'",user);
                var results=parallel(()->tasks.admitDetail(input(admissionRequest,admissionSession,"PG受理并发")));
                require(results.stream().filter(TaskAdmitOutcome::created).count()==1,"one admission winner");admissionTask=results.getFirst().taskId();
                require(scalar("SELECT count(*) FROM rag_requests WHERE request_id=?",admissionRequest)==1,"one task");require(scalar("SELECT count(*) FROM rag_task_inputs WHERE task_id=?",admissionTask)==1,"one input");
                require(scalar("SELECT COALESCE(SUM(chat_count),0) FROM usage_counters WHERE subject=? AND kind='chat'",user)==before+1,"one charge");
                require(scalar("SELECT count(*) FROM assistant_messages WHERE session_id=? AND role='user'",admissionSession)==1,"one user message");out.put("workers",12);
            }
            case "pg-admission-rollback" -> {
                String id=UUID.randomUUID().toString().replace("-", "");
                try {tx.required(t->{try{store.insertRequest(t,id,UUID.randomUUID().toString(),TaskInputHash.compute("PG受理回滚"),false,"chat",user,admissionSession,"PG受理回滚","","synthetic");throw new InjectedFailure();}catch(SQLException e){throw new IllegalStateException(e);}});throw new IllegalStateException("注入未生效");}catch(InjectedFailure expected){}
                require(scalar("SELECT count(*) FROM rag_requests WHERE id=?",id)==0,"no orphan after insert fault");require(scalar("SELECT count(*) FROM assistant_messages WHERE session_id=? AND role='user'",admissionSession)==1,"no second user message");
            }
            case "pg-finish-rollback" -> {
                boolean failed=false;try{tasks.finishDetail(admissionTask,"p0-nonexistent-session","合成答案",List.of(),"answered","supported",release,1);}catch(RuntimeException expected){failed=true;}
                require(failed,"real FK fault");require(store.findOwned(admissionTask,user).orElseThrow().status().equals("pending"),"pending preserved");require(store.loadInputs(admissionTask).isPresent(),"inputs retained");
                require(scalar("SELECT count(*) FROM assistant_messages WHERE session_id=? AND role='assistant'",admissionSession)==0,"no assistant partial commit");
            }
            case "pg-finish-once" -> {
                var first=tasks.finishDetail(admissionTask,admissionSession,"合成最终答案",List.of(),"answered","supported",release,1);boolean rejected=false;
                try{tasks.finishDetail(admissionTask,admissionSession,"不得重复",List.of(),"answered","supported",release,1);}catch(RuntimeException expected){rejected=true;}
                require(rejected,"duplicate finish rejected");require(store.loadInputs(admissionTask).isEmpty(),"terminal clears input");
                require(scalar("SELECT count(*) FROM assistant_messages WHERE session_id=? AND role='assistant'",admissionSession)==1,"one assistant");
                var probe=tasks.probeDetail(new RequestIdentity(admissionRequest,TaskInputHash.compute("PG受理并发"),false,RequestIdentity.Purpose.chat),user,Optional.of(admissionSession));
                require(probe.row().orElseThrow().messageId().equals(first.messageId()),"replay message identity");
            }
            case "pg-cancel-idempotent" -> {
                cancelSession=session("PG取消");cancelTask=begin(cancelSession,"PG取消");tx.required(t->{try{store.storeInputs(t,cancelTask,"PG取消",null,null,"/* synthetic temporary source */","pasted");return null;}catch(SQLException e){throw new IllegalStateException(e);}});
                store.recordJob(cancelTask,"p0-cancel-job","grade_plugin","dns","0".repeat(64));
                require(store.cancelTask(cancelTask,"user_cancelled",user),"first cancel changes");require(!store.cancelTask(cancelTask,"user_cancelled",user),"second cancel no-op");require(store.loadInputs(cancelTask).isEmpty(),"cancel clear");
                require(grades.recordTaskGrade(new TaskGradeInput(cancelTask,"p0-cancel-job","dns","0".repeat(64),80,4,5,"[]"))==TaskGradeResult.cancelled,"cancelled grades blocked");
                require(scalar("SELECT count(*) FROM grading_submissions WHERE task_job_id='p0-cancel-job'")==0,"no grade");
            }
            case "pg-grade-job-dedup" -> {
                gradeSession=session("PG成绩去重");gradeTask=begin(gradeSession,"PG成绩去重");store.recordJob(gradeTask,"p0-dedup-job","grade_plugin","dns","1".repeat(64));
                // 冻结grade_args显式source_origin=pasted；当前生产参与者从真实临时输入读取来源。
                // 用同case短期真实寄存传递等价输入，完成后立即清理，不伪填成绩JSON。
                tx.required(t->{try{store.storeInputs(t,gradeTask,"PG成绩去重",null,null,null,"pasted");return null;}catch(SQLException e){throw new IllegalStateException(e);}});
                var results=parallel(()->grades.recordTaskGrade(new TaskGradeInput(gradeTask,"p0-dedup-job","dns","1".repeat(64),80,4,5,"[]")));
                store.dropInputs(gradeTask);
                require(results.stream().allMatch(x->x==TaskGradeResult.persisted),"all callers persisted");require(scalar("SELECT count(*) FROM grading_submissions WHERE task_job_id='p0-dedup-job'")==1,"one grade");require(scalar("SELECT submission_done FROM rag_task_jobs WHERE job_id='p0-dedup-job'")==1,"submission flag");out.put("workers",12);
            }
            case "pg-recovery-claim" -> {
                sql("UPDATE rag_requests SET updated_at='2000-01-01T00:00:00+00:00' WHERE id=?",gradeTask);
                var results=parallel(()->store.claimRecovery(gradeTask,Instant.parse("2026-10-24T04:09:30Z")));require(results.stream().filter(x->x).count()==1,"one recovery claimant");out.put("workers",12);
            }
            case "pg-input-ttl" -> {
                tx.required(t->{try{store.storeInputs(t,gradeTask,"合成TTL",null,null,null,null);return null;}catch(SQLException e){throw new IllegalStateException(e);}});
                require(store.loadInputs(gradeTask).isPresent(),"within TTL readable");sql("UPDATE rag_task_inputs SET expires_at='2026-10-24T03:59:59+00:00' WHERE task_id=?",gradeTask);require(store.loadInputs(gradeTask).isEmpty(),"expired removed");
            }
            case "pg-vector-rrf-mmr" -> {
                List<String> semantic=new ArrayList<>();try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement("SELECT node_id FROM data_li_knowledge_v1 ORDER BY embedding <=> ?::vector,node_id LIMIT 10")){
                    String vector="[1"+",0".repeat(1023)+"]";p.setString(1,vector);try(var r=p.executeQuery()){while(r.next())semantic.add(r.getString(1));}}
                require(semantic.equals(List.of("li_p0_node_0","li_p0_node_1","li_p0_node_2")),"exact cosine order");float[] vec=new float[1024];vec[0]=1;
                var result=retrieval.searchPinned(retrieval.pin(release),new SearchInput("DNS",new Vector1024(vec)),6);var trace=(RetrievalTrace)result.trace();
                require(!trace.nodes().isEmpty()&&trace.nodes().getFirst().id().equals("li_p0_node_0"),"RRF/MMR top");
                out.put("semantic_ids",semantic);out.put("ranked",trace.nodes().stream().map(n->Map.of("chunk_id",n.id(),"score",n.score(),"source_path",n.sourcePath())).toList());
                out.put("tolerance",Map.of("raw_ranked_score_absolute",1e-12,"authorization","U09 user accepted","other_business_scores","exact","order","exact; semantic ties node_id ASC"));
            }
            case "citation-six-same-file" -> {
                // 用实际finish+PG回读证明保序保重；外层事务回滚，让纯引用专例不污染业务状态链。
                try {tx.required(t->{
                    try {
                        String sid=session("J5六引用回滚观察"),tid=begin(sid,"同文件六引用");List<CitationData> input=new ArrayList<>();
                        for(int i=0;i<6;i++)input.add(new CitationData("li_six_"+i,"synthetic/same.md","同文件片段",Integer.toString(i),1.0-i/10.0));
                        String mid=tasks.finishDetail(tid,sid,"六条合成引用",input,"answered","supported",release,1).messageId();
                        var rows=new ArrayList<Map<String,Object>>();
                        List<Map<String,Object>> evidence;
                        try(var p=t.connection().prepareStatement("SELECT evidence_json FROM rag_requests WHERE id=?")){p.setString(1,tid);try(var r=p.executeQuery()){r.next();evidence=new com.fasterxml.jackson.databind.ObjectMapper().readValue(r.getString(1),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});}}
                        try(var p=t.connection().prepareStatement("SELECT chunk_id,score,rank FROM assistant_citations WHERE message_id=? ORDER BY rank")){p.setString(1,mid);try(var r=p.executeQuery()){int i=0;while(r.next()){
                            require(r.getInt(3)==i+1,"citation rank order");String chunk=r.getString(1);require(chunk.equals("li_six_"+i),"citation identity order");
                            var ev=evidence.get(i);require(chunk.equals(ev.get("chunk_id")),"evidence identity association");
                            rows.add(Map.of("chunk_id",chunk,"score",r.getDouble(2),"source_path",ev.get("source_path"),"section",ev.get("section"),"title",input.get(i).title()));i++;}}
                        }
                        require(rows.size()==6,"all six same-file references retained");throw new CitationRollback(rows);
                    }catch(java.sql.SQLException|com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
                });throw new IllegalStateException("citation rollback did not fire");}catch(CitationRollback observed){out.put("citations",observed.rows);}
            }
            case "pg-cleanup-terminal" -> {require(store.markInterrupted(gradeTask,"p0-synthetic-end"),"pending close");require(scalar("SELECT count(*) FROM rag_requests WHERE status='pending'")==0,"zero pending");require(scalar("SELECT count(*) FROM rag_task_inputs")==0,"zero transient input");}
            default -> throw new IllegalArgumentException("未实现的真实事务专例: "+name);
        }
        out.put("status","通过");return out;
    }
    private static class CitationRollback extends RuntimeException {
        final List<Map<String,Object>> rows;
        CitationRollback(List<Map<String,Object>> rows){this.rows=rows;}
    }
    private static class InjectedFailure extends RuntimeException {}
}
