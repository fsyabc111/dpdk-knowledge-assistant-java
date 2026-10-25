package io.dpdk.assistant.operations.adapter;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.index.*;
import io.dpdk.assistant.knowledge.workflow.*;
import io.dpdk.assistant.operations.OperationsPort;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.task.TaskTestHarness;
import io.dpdk.assistant.usage.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** 全域仓储、真实 PG/事务与固定向量；只有模型嵌入边界为计数替身，不访问上游。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class OperationsAdaptersIntegrationTest {
    TaskTestHarness h;
    KnowledgeWorkflowService workflow;
    OperationsBusinessAdapter ops;
    UsageLedgerStore ledger;
    @TempDir Path temp;
    @BeforeAll void setup() throws Exception {
        Assumptions.assumeTrue(TestPg.envPresent());
        h = TaskTestHarness.create("j403adapters");
        ledger = new UsageLedgerStore(h.runner);
        workflow = new KnowledgeWorkflowService(h.runner, h.clock, new OriginalQuestionVerifier() {
            public void prepareAdmission(Principal p) { throw new AssertionError("维护不得进入模型核验"); }
            public Result verify(Principal p, String q, String c) { throw new AssertionError("维护不得外发"); }
        });
        ops = adapter(null, null, Map.of());
    }
    @AfterAll void teardown() throws Exception { if (h != null) h.drop(); }
    @BeforeEach void reset() throws Exception {
        sql("TRUNCATE users,assistant_sessions,rag_requests,rag_task_inputs,rag_task_jobs,rag_issues,rag_issue_events,"
                + "rag_knowledge,rag_knowledge_revisions,upstream_usage_records,grading_submissions,rag_release_nodes,rag_releases,data_li_knowledge_v1 CASCADE");
        sql("UPDATE rag_index_state SET release_id='' WHERE name='active'");
        h.clock.setWall(TaskTestHarness.T0);
    }
    OperationsBusinessAdapter adapter(IndexService index, PgReleaseReader reader, Map<String, Vector1024> queries) {
        return new OperationsBusinessAdapter(h.adminUsers(), h.sessions, h.store, workflow, index, reader,
                manifest -> new SourceSettings(manifest, temp, temp, temp), queries);
    }
    String iso(Instant t) { return SystemClockAdapter.pythonIso(t); }
    String before(long seconds) { return iso(h.clock.nowUtc().minusSeconds(seconds)); }
    void request(String id, String status, int ageDays, int idleMinutes) throws Exception {
        sql("INSERT INTO rag_requests(id,question,status,created_at,updated_at) VALUES (?, '合成问题',?,?,?)",
                id, status, before(ageDays*86400L), before(idleMinutes*60L));
    }
    void input(String id, int remainingSeconds) throws Exception {
        sql("INSERT INTO rag_task_inputs(task_id,question,source_text,created_at,expires_at) VALUES (?,'合成输入','synthetic-only',?,?)",
                id, before(86400L-remainingSeconds), iso(h.clock.nowUtc().plusSeconds(remainingSeconds)));
    }
    void sql(String sql, Object... args) throws Exception {
        try (Connection c=h.ds.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) p.setObject(i+1,args[i]); p.execute();
        }
    }
    Map<String,String> snapshot() throws Exception {
        Map<String,String> out=new LinkedHashMap<>();
        try(Connection c=h.ds.getConnection(); Statement st=c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet r = st.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for(String table:tables) try(ResultSet r=st.executeQuery("SELECT coalesce(jsonb_agg(x ORDER BY x::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS x FROM "+table+" t) q")) {
                r.next();out.put(table,r.getString(1));
            }
        }
        return out;
    }
    @Test void userPreviewUsesCreateRulesAndNeverTouchesDatabase() throws Exception {
        h.seedUser("U00000000001","[\"viewer\"]",3);
        var request=new OperationsPort.CreateUsers(List.of("U00000000001","U00000000002","U00000000002","bad"),List.of("viewer"),7);
        var before=snapshot();
        var preview=ops.createUsers(request,false);
        assertThat(preview.get("would_create")).isEqualTo(List.of("U00000000002"));
        assertThat(preview.get("existing")).isEqualTo(List.of("U00000000001","U00000000002"));
        assertThat(preview.get("invalid")).isEqualTo(List.of("bad"));
        assertThat(preview.get("skipped")).isEqualTo(List.of("U00000000001", "U00000000002", "bad"));
        assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->ops.createUsers(new OperationsPort.CreateUsers(List.of("U00000000003"),List.of("bad"),2),false)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->ops.createUsers(new OperationsPort.CreateUsers(List.of("U00000000003"),List.of("viewer"),-1),false)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ops.createUsers(request,true).get("created")).isEqualTo(List.of("U00000000002"));
        assertThat(ops.createUsers(request,false).get("would_create")).isEqualTo(List.of());
    }
    @Test void inputDryRunZeroWritesThenCleansTerminalOrphanAnd24HourExpiry() throws Exception {
        request("terminal","answered",0,0); input("terminal",3600);
        request("expired","pending",1,60); input("expired",-1);
        request("recoverable","pending",0,60); input("recoverable",3600);
        input("orphan",3600);
        var before=snapshot();
        assertThat(ops.purgeTaskInputs(false).get("inputs")).isEqualTo(3L);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ops.purgeTaskInputs(true).get("inputs")).isEqualTo(3L);
        assertThat(h.countRows("rag_task_inputs","task_id='recoverable'")).isEqualTo(1);
        assertThat(h.countRows("rag_task_inputs",null)).isEqualTo(1);
        assertThat(ops.purgeTaskInputs(false).get("inputs")).isEqualTo(0L);
    }
    @Test void retainUsesWorkflowSameTransactionAndPreservesRecoverableInputs() throws Exception {
        request("old","answered",181,0); input("old",3000);
        sql("INSERT INTO rag_issues(id,request_id,owner_id,updated_at) VALUES ('old','old','gone',?)",before(0));
        sql("INSERT INTO rag_issue_events(id,issue_id,actor_id,action,detail,created_at) VALUES ('event','old','gone','feedback','synthetic',?)",before(0));
        sql("INSERT INTO rag_knowledge(id,title,body,created_by,updated_at) VALUES ('knowledge','合成','内容','gone',?)",before(0));
        sql("INSERT INTO rag_knowledge_revisions(id,knowledge_id,revision,title,body,actor_id,status,created_at) VALUES ('revision','knowledge',1,'合成','内容','gone','published',?)",before(0));
        h.store.recordJob("old","job","grade_plugin",null,null);
        request("stale","pending",0,11);
        request("recoverable","pending",0,11); input("recoverable",3600);
        request("refs","answered",0,0);
        sql("UPDATE rag_requests SET user_id='gone',session_id='gone' WHERE id='refs'");
        var before=snapshot();
        assertThat(ops.retainIssues(180,false).get("interrupted")).isEqualTo(1L);
        assertThat(snapshot()).isEqualTo(before); // 包括 updated_at/全部输入/用户与工作流行。
        var result=ops.retainIssues(180,true);
        assertThat(result.get("expired")).isEqualTo(1L);
        assertThat(result.get("workflow")).isEqualTo(Map.of("events",1,"issues",1));
        assertThat(h.countRows("rag_requests","id='old'")).isZero();
        assertThat(h.countRows("rag_task_jobs",null)).isZero();
        assertThat(h.countRows("rag_issue_events",null)).isZero();
        assertThat(h.columnValue("rag_requests","reason","id='stale'")).isEqualTo("interrupted");
        assertThat(h.columnValue("rag_requests","status","id='recoverable'")).isEqualTo("pending");
        assertThat(h.countRows("rag_task_inputs","task_id='recoverable'")).isEqualTo(1);
        assertThat(h.columnValue("rag_requests","user_id","id='refs'")).isNull();
        assertThat(h.columnValue("rag_requests","session_id","id='refs'")).isNull();
        assertThat(h.columnValue("rag_knowledge","created_by","id='knowledge'")).isNull();
        assertThat(h.columnValue("rag_knowledge_revisions","actor_id","id='revision'")).isNull();
        var applied=snapshot();
        assertThat(ops.retainIssues(180,false).get("interrupted")).isEqualTo(0L);
        assertThat(ops.retainIssues(180,true).get("expired")).isEqualTo(0L);
        assertThat(snapshot()).isEqualTo(applied);
    }
    @Test void workflowFailureRollsBackInterruptionAndInputCleanup() throws Exception {
        request("stale","pending",0,11);request("terminal","answered",0,0);input("terminal",600);
        var before=snapshot();
        assertThatThrownBy(()->h.store.maintain(180,false,(tx,days)->{
            workflow.maintainInTx(tx,days);throw new IllegalStateException("合成故障");
        })).hasMessageContaining("合成故障");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void sessionsAndAuditAreRealReadonlyPreviews() throws Exception {
        h.seedSession("old-session",null);
        sql("UPDATE assistant_sessions SET updated_at=? WHERE id='old-session'",OffsetDateTime.ofInstant(h.clock.nowUtc().minus(Duration.ofDays(31)),ZoneOffset.UTC));
        request("audit","answered",0,0);h.store.recordJob("audit","grading-id","grade_plugin","synthetic",null);
        h.store.finishJob("grading-id","{\"private_output\":\"must-not-emit\"}");
        var before=snapshot();
        assertThat(ops.purgeSessions(30,false).get("sessions")).isEqualTo(1);
        assertThat(ops.auditGradingJobs(20).toString()).contains("grading-id").doesNotContain("must-not-emit");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ops.purgeSessions(30,true).get("sessions")).isEqualTo(1);
    }
    @Test void metricsReadDurableKnownValuesAndKeepUnknownMissing() throws Exception {
        request("answered","answered",0,0);sql("UPDATE rag_requests SET reason='supported',elapsed_ms=2500 WHERE id='answered'");
        ledger.recordRows(List.of(
                new UsageLedgerRow("known",null,null,null,"llm","finalize","m1",4,5,9,"upstream",1,200,25,"USD","v1",before(0)),
                new UsageLedgerRow("known2",null,null,null,"llm","finalize","m2",1,2,3,"upstream",1,100,7,"USD","v1",before(0)),
                new UsageLedgerRow("unknown",null,null,null,"llm","finalize","m3",null,null,null,"unknown",1,null,null,null,null,before(0))));
        var before=snapshot();
        var metrics=new OperationsDomainMetricsAdapter(h.store,ledger).snapshot();
        assertThat(metrics.samples().stream().filter(s->s.name().equals("assistant_upstream_usage_tokens_total")).map(s->s.value()).toList()).containsExactly(12.0);
        assertThat(metrics.samples().stream().filter(s->s.name().equals("assistant_upstream_usage_unknown_total")).map(s->s.value()).toList()).containsExactly(1.0);
        assertThat(metrics.samples().stream().filter(s->s.name().equals("assistant_upstream_usage_tokens_total"))).noneMatch(s->s.labels().get("source").equals("unknown"));
        assertThat(metrics.unavailable()).contains("assistant_agent_tool_duration_seconds","assistant_rate_limit_hits_total");
        assertThat(metrics.samples().stream().filter(s->s.name().equals("assistant_rag_outcomes_total")).map(s->s.value()).toList()).containsExactly(1.0);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void indexDryRunsAndFixedRetrievalNeverCallUnconfiguredUpstreams() throws Exception {
        AtomicInteger embedded=new AtomicInteger(),flushed=new AtomicInteger();
        var config=new EmbeddingConfig("synthetic","http://127.0.0.1:1/v1",1024,"bounded-lines-v2");
        var fingerprint=new IndexConfigurationFingerprint();
        var index=new IndexService(h.ds,h.runner,RetrievalSettings.defaults(),config,fingerprint,h.clock,
                (texts,usage)->{embedded.incrementAndGet();return texts.stream().map(t->vector()).toList();}, usage->flushed.incrementAndGet(),10,
                new io.dpdk.assistant.knowledge.workflow.PublishedKnowledgeRepository(h.runner));
        var reader=new PgReleaseReader(h.ds,RetrievalSettings.defaults(),config,fingerprint,new HeuristicQueryRewriter(),
                input->{throw new AssertionError("固定检索禁止调用 embedding 上游");});
        var configured=adapter(index,reader,Map.of("DPDK",vector()));
        Path manifest=temp.resolve("sources.yml");
        Files.writeString(temp.resolve("a.md"),"# DPDK\nDPDK synthetic documentation");
        Files.writeString(manifest,"sources:\n  - name: fixture\n    root: ${DPDK_REPO_PATH}\n    include: [a.md]\n");
        var before=snapshot();
        assertThat(configured.ingest(manifest,false).get("changed")).isEqualTo(1);
        assertThat(embedded).hasValue(0);assertThat(flushed).hasValue(0);assertThat(snapshot()).isEqualTo(before);
        configured.ingest(manifest,true);
        String first=index.activeRelease();assertThat(embedded).hasValue(1);assertThat(flushed).hasValue(1);
        before=snapshot();configured.rollback(first,false);assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->configured.rollback("missing",false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(configured.retrieve("DPDK",5).get("non_live")).isEqualTo(true);
        assertThatThrownBy(()->configured.retrieve("outside-boundary",5)).isInstanceOf(IllegalStateException.class);
        String chunk=h.columnValue("data_li_knowledge_v1","node_id","true");
        Path cases=temp.resolve("cases.json");
        Files.writeString(cases,new ObjectMapper().writeValueAsString(List.of(Map.of("question","DPDK","expected_chunk_ids",List.of(chunk)))));
        assertThat(configured.evaluateRetrieval(cases)).containsEntry("domain","deterministic_retrieval").containsEntry("non_live",true).containsEntry("release", first);
        assertThatThrownBy(()->ops.retrieve("DPDK",5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->ops.evaluateRetrieval(cases)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ops.judge(cases)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
        Files.writeString(temp.resolve("a.md"),"# DPDK\nDPDK updated synthetic documentation");
        configured.ingest(manifest,true);assertThat(index.activeRelease()).isNotEqualTo(first);
        configured.rollback(first,true);assertThat(index.activeRelease()).isEqualTo(first);
    }
    static Vector1024 vector() { float[] v=new float[1024];v[0]=1;return new Vector1024(v); }
}
