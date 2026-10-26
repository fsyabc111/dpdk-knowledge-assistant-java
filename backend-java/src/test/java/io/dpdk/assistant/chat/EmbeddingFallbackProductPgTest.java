package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import io.dpdk.assistant.retrieval.EmbeddingFailurePgTest;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实产品/PG检索/计量/引用闭环；仅外部embedding在loopback失败，模型固定。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class EmbeddingFallbackProductPgTest {
    @Test void lexicalEvidenceStillAnswersWithRealCitationAndUnknownFailedUsage() throws Exception {
        runProduct("DNS labels",true);
    }
    @Test void withoutActualCandidateTheProductReportsRetrievalFailedAndNoFakeCitation() throws Exception {
        runProduct("unmatchedzzzzword",false);
    }

    private void runProduct(String query,boolean hasEvidence) throws Exception {
        try(var f=new EmbeddingFailurePgTest.Fixture()) {
            TaskTestHarness h=TaskTestHarness.attach(f.database);
            try {
                h.seedUser("student","[\"viewer\"]",10000);
                AgentChatModel model=(messages,tools)->messages.getLast().isTool()
                        ?new AgentChatModel.AgentReply("DNS 标签按长度编码 [1]。",List.of(),new AgentUsage(10,2,12))
                        :new AgentChatModel.AgentReply("",List.of(ToolCallSpec.of("fallback-search","kb_search",Map.of("query",query))),new AgentUsage(10,2,12));
                var executor=new ChatTaskExecutor(h.sessions,h.runner,h.clock,model,AgentSettings.defaults(),f.reader,null,
                        h.store,new TaskGradeLedger(h.store,h.guard,h.runner,h.clock),h.counters,new UsageLedgerStore(h.runner),ChatSettings.defaults());
                var product=new ProductChatService(h.service,h.gate,h.sessions,executor,ChatSettings.defaults(),h.clock);
                h.setRecoveryExecutor(executor);
                AtomicInteger fallback=new AtomicInteger();
                ProcessMetrics metrics=new ProcessMetrics(){public void fallback(){fallback.incrementAndGet();}};
                executor.setMetrics(metrics);product.setMetrics(metrics);
                var student=new Principal("student",List.of("viewer"),10000,true);
                var input=new ChatInput(null,UUID.randomUUID().toString(),query,Map.of(),null);
                var result=product.chat(student,input);
                if(hasEvidence) {
                    assertThat(result.answerStatus()).isEqualTo("answered");
                    assertThat(result.citations()).extracting(CitationData::chunkId).containsExactly("li_embedding_failure_dns");
                    assertThat(result.citations()).extracting(CitationData::sourcePath).containsExactly("src/dns.c");
                } else {
                    assertThat(result.answerStatus()).isEqualTo("system_error");
                    assertThat(result.answerReason()).isEqualTo("retrieval_failed");
                    assertThat(result.citations()).isEmpty();
                }
                assertThat(fallback).hasValue(1);
                assertThat(f.requests).hasValue(1);
                assertThat(h.countRows("rag_requests","id=? AND status<>'pending'",result.taskId())).isEqualTo(1);
                assertThat(h.service.loadInputs(result.taskId())).isEmpty();
                try(var connection=f.ds.getConnection();var ps=connection.prepareStatement(
                        "SELECT usage_source,call_count,prompt_tokens,completion_tokens,total_tokens,cost_micros FROM upstream_usage_records WHERE request_id=? AND kind='embedding' AND stage='query_embed'")) {
                    ps.setString(1,input.requestId());
                    try(var usage=ps.executeQuery()) {
                        assertThat(usage.next()).isTrue();assertThat(usage.getString(1)).isEqualTo("unknown");
                        assertThat(usage.getInt(2)).isEqualTo(1);
                        for(int index=3;index<=6;index++) assertThat(usage.getObject(index)).isNull();
                        assertThat(usage.next()).isFalse();
                    }
                }
                int rows=h.countRows("upstream_usage_records",null);
                var replay=product.chat(student,input);
                assertThat(replay.taskId()).isEqualTo(result.taskId());
                assertThat(fallback).hasValue(1);assertThat(f.requests).hasValue(1);
                assertThat(h.countRows("upstream_usage_records",null)).isEqualTo(rows);
                assertThat(h.columnValue("usage_counters","chat_count","subject='student' AND kind='chat'")).isEqualTo("1");
            } finally {h.taskRunner.shutdownWatchdog();}
        }
    }
}
