package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import io.dpdk.assistant.model.EmbeddingClientSettings;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.model.UpstreamGate;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.task.TaskGradeLedger;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真PG/模型HTTP边界，闩锁控制shared loader晚于任务终态返回，禁止sleep碰绿。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL", matches=".+")
class LateUsageClosureIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void cancelledOwnerPersistsExactlyOneLateEmbeddingReceiptAfterItsTerminalCheckpoint(boolean unknownUsage) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch lateCommitted = new CountDownLatch(1);
        AtomicInteger httpCalls = new AtomicInteger();
        List<ProcessMetrics.UsageSample> samples = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes(); httpCalls.incrementAndGet(); entered.countDown();
            try {
                if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test response gate timeout");
                List<Integer> vector = new ArrayList<>();
                for(int i=0;i<1024;i++) vector.add(i==0?1:0);
                Map<String,Object> response=new java.util.LinkedHashMap<>();
                response.put("data",List.of(Map.of("index",0,"embedding",vector)));
                if(!unknownUsage) response.put("usage",Map.of("prompt_tokens",9,"total_tokens",9));
                byte[] body=new ObjectMapper().writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        AgentChatModel model=(messages,tools)-> messages.stream().anyMatch(m->m.isTool())
                ?AgentChatModel.AgentReply.text("DNS evidence [1]")
                :new AgentChatModel.AgentReply("",List.of(new ToolCallSpec("kb","kb_search",Map.of("query","DNS"))),null);
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        try(var h=ChatTestHarness.create("lateusage",model,null);
            var client=new AutoEmbedding(endpoint)) {
            h.seedKnowledge("late-release","late-dns-node","DNS protocol implementation evidence");
            var config=new EmbeddingConfig("synthetic-embedding",endpoint,1024,"bounded-lines-v2");
            try(var c=h.task.ds.getConnection();var ps=c.prepareStatement("UPDATE rag_releases SET configuration=? WHERE id='late-release'")) {
                ps.setString(1,new IndexConfigurationFingerprint().pythonCompatibleConfiguration(config));ps.executeUpdate();
            }
            var reader=new PgReleaseReader(h.task.ds,RetrievalSettings.defaults(),config,new IndexConfigurationFingerprint(),
                    new HeuristicQueryRewriter(),null,(text,ledger,stage)->client.value.embedTexts(List.of(text),ledger,stage)
                            .stream().map(Vector1024::new).toList());
            var usage=new UsageLedgerStore(h.task.runner);
            usage.setMetrics(new ProcessMetrics() {
                @Override public void usage(UsageSample sample) {
                    samples.add(sample);
                    if("query_embed".equals(sample.stage())) lateCommitted.countDown();
                }
            });
            var settings=ChatSettings.defaults();
            var executor=new ChatTaskExecutor(h.task.sessions,h.task.runner,h.task.clock,model,AgentSettings.defaults(),reader,
                    (tool,args,deadline)->{throw new AssertionError("knowledge request must not call MCP");},h.task.store,
                    new TaskGradeLedger(h.task.store,h.task.guard,h.task.runner,h.task.clock),h.task.counters,usage,settings);
            var product=new ProductChatService(h.task.service,h.task.gate,h.task.sessions,executor,settings,h.task.clock);
            h.task.setRecoveryExecutor(executor);
            var input=h.input("DNS");
            var start=product.start(h.student,input);
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            long cancelledAt=System.nanoTime();
            h.task.service.cancel(start.taskId(),"student");
            assertThat(start.managed().awaitDone(Duration.ofSeconds(2))).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime()-cancelledAt)).isLessThan(Duration.ofSeconds(2));
            assertThat(h.task.columnValue("rag_requests","status","id=?",start.taskId())).isEqualTo("cancelled");
            assertThat(h.task.countRows("rag_task_inputs","task_id=?",start.taskId())).isZero();
            assertThat(h.task.countRows("upstream_usage_records","request_id=? AND stage='query_embed'",input.requestId())).isZero();
            // HTTP仍被关在闩锁后；取消不能等响应，也不能杀掉共享loader。
            release.countDown();
            assertThat(lateCommitted.await(3,TimeUnit.SECONDS)).as("终态后真实HTTP收据仍须归原request并afterCommit计量").isTrue();
            assertThat(httpCalls.get()).isEqualTo(1);
            assertThat(h.task.countRows("upstream_usage_records","request_id=? AND stage='query_embed'",input.requestId())).isEqualTo(1);
            assertThat(h.task.columnValue("upstream_usage_records","total_tokens","request_id=? AND stage='query_embed'",input.requestId()))
                    .isEqualTo(unknownUsage?null:"9");
            assertThat(h.task.columnValue("upstream_usage_records","usage_source","request_id=? AND stage='query_embed'",input.requestId()))
                    .isEqualTo(unknownUsage?"unknown":"upstream");
            assertThat(h.task.columnValue("upstream_usage_records","call_count","request_id=? AND stage='query_embed'",input.requestId())).isEqualTo("1");
            assertThat(samples.stream().filter(s->"query_embed".equals(s.stage())).count()).isEqualTo(1);
        } finally { release.countDown();server.stop(0); }
    }
    private static final class AutoEmbedding implements AutoCloseable {
        final OpenAiCompatibleEmbeddingClient value;
        AutoEmbedding(String endpoint) {
            value=new OpenAiCompatibleEmbeddingClient(new EmbeddingClientSettings(endpoint,"synthetic-key","synthetic-embedding",10,0,0,5,()->0),new UpstreamGate(2));
        }
        @Override public void close() { value.close(); }
    }
}
