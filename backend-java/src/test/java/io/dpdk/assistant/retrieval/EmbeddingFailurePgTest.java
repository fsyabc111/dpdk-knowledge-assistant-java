package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.*;
import io.dpdk.assistant.model.*;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.usage.UsageCore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** SP-P5-01：真实PG四腿检索与loopback embedding失败；不返回伪造chunk。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
public class EmbeddingFailurePgTest {
    private static final String FALLBACK = "query embedding failed; retrieval used lexical/path candidates";

    /** 跨retrieval/chat用的独立数据库+真实外部客户端；只在本轮DB写固定合成节点。 */
    public static final class Fixture implements AutoCloseable {
        public final String database;
        public final DataSource ds;
        public final HttpServer endpoint;
        public final OpenAiCompatibleEmbeddingClient client;
        public final EmbeddingConfig config;
        public final AtomicInteger requests = new AtomicInteger();
        public final AtomicBoolean healthy = new AtomicBoolean();
        public final PgReleaseReader reader;
        public Fixture() throws Exception {
            database = TestPg.createDatabase("p5embedfallback",TestPg.COMMENT);
            ds = TestPg.dataSource(database);
            new MigrationController().run(ds,TestPg.identity(database),true);
            endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            endpoint.createContext("/",exchange -> {
                requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                String vector = "[1,"+String.join(",",Collections.nCopies(1023,"0"))+"]";
                String body = healthy.get() ? "{\"data\":[{\"index\":0,\"embedding\":"+vector+"}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"
                        : "{\"error\":\"synthetic embedding unavailable\"}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(healthy.get()?200:503,bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            endpoint.start();
            String base="http://127.0.0.1:"+endpoint.getAddress().getPort()+"/"+UUID.randomUUID();
            config=new EmbeddingConfig("text-embedding-v4",base,1024,"bounded-lines-v2");
            client=new OpenAiCompatibleEmbeddingClient(new EmbeddingClientSettings(base,"local-synthetic-key","text-embedding-v4",10,0,0,2,()->0),new UpstreamGate(4));
            reader=reader((input,ledger,stage)->client.embedTexts(List.of(input),ledger,stage).stream().map(Vector1024::new).toList());
            seed();
        }
        public PgReleaseReader reader(PgReleaseReader.QueryEmbeddingClient embedding) {
            return new PgReleaseReader(ds,RetrievalSettings.defaults(),config,new IndexConfigurationFingerprint(),
                    new HeuristicQueryRewriter(null,config.apiBase(),"qwen3.8-flash"),null,embedding);
        }
        private void seed() throws Exception {
            try(var conn=ds.getConnection()) {
                try(var ps=conn.prepareStatement("INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (?,?,?::jsonb,?::vector)")) {
                    ps.setString(1,"li_embedding_failure_dns");ps.setString(2,"DNS labels use length encoding. DNS 标签按长度编码。");
                    ps.setString(3,"{\"source_path\":\"src/dns.c\",\"title\":\"DNS\",\"section\":\"labels\"}");
                    ps.setString(4,"[1,"+String.join(",",Collections.nCopies(1023,"0"))+"]");ps.executeUpdate();
                }
                try(var ps=conn.prepareStatement("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES ('fallback-release',?,'2026-10-26T00:00:00+00:00',1)")) {
                    ps.setString(1,new IndexConfigurationFingerprint().pythonCompatibleConfiguration(config));ps.executeUpdate();
                }
                try(var sql=conn.createStatement()) {
                    sql.executeUpdate("INSERT INTO rag_release_nodes(release_id,node_id) VALUES ('fallback-release','li_embedding_failure_dns')");
                    sql.executeUpdate("UPDATE rag_index_state SET release_id='fallback-release' WHERE name='active'");
                }
            }
        }
        @Override public void close() throws Exception { client.close();endpoint.stop(0);TestPg.dropDatabase(database); }
    }

    @Test void httpFailureKeepsRealLexicalCandidatesUnknownUsageAndDoesNotPoisonCache() throws Exception {
        try(var f=new Fixture()) {
            for(int attempt=1;attempt<=2;attempt++) {
                var usage=new UsageCore.UsageRecorder();
                var result=f.reader.searchPinned(f.reader.pin(""),new SearchInput("DNS labels",null,null),6,usage);
                var trace=(RetrievalTrace)result.trace();
                assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly("li_embedding_failure_dns");
                assertThat(trace.blockedReason()).isNull();assertThat(trace.embeddingDim()).isZero();
                assertThat(trace.semanticCandidates()).isZero();assertThat(trace.ftsOriginalCandidates()).isPositive();
                assertThat(trace.nodes().getFirst().sourcePath()).isEqualTo("src/dns.c");
                assertThat(trace.fallbacks()).contains(FALLBACK);
                var embedding=usage.entries().stream().filter(e->e.kind().equals("embedding")).toList();
                assertThat(embedding).hasSize(1);assertThat(embedding.getFirst().usageSource()).isEqualTo("unknown");
                assertThat(embedding.getFirst().callCount()).isEqualTo(1);
                assertThat(embedding.getFirst().totalTokens()).isNull();assertThat(embedding.getFirst().promptTokens()).isNull();
                assertThat(f.requests).hasValue(attempt);
            }
            f.healthy.set(true);
            var actual=new UsageCore.UsageRecorder();
            var restored=f.reader.searchPinned(f.reader.pin(""),new SearchInput("DNS labels",null,null),6,actual);
            assertThat(((RetrievalTrace)restored.trace()).semanticCandidates()).isPositive();
            assertThat(((RetrievalTrace)restored.trace()).fallbacks()).doesNotContain(FALLBACK);
            assertThat(actual.entries().stream().filter(e->e.kind().equals("embedding")).findFirst().orElseThrow().totalTokens()).isEqualTo(2);
            var cached=new UsageCore.UsageRecorder();
            f.reader.searchPinned(f.reader.pin(""),new SearchInput("DNS labels",null,null),6,cached);
            assertThat(f.requests).hasValue(3);
            assertThat(cached.entries().stream().filter(e->e.kind().equals("embedding")).findFirst().orElseThrow().usageSource()).isEqualTo("cache_hit");
        }
    }

    @Test void failedEmbeddingWithoutRealCandidateReturnsEmptyAndExplicitFallback() throws Exception {
        try(var f=new Fixture()) {
            var result=f.reader.searchPinned(f.reader.pin(""),new SearchInput("unmatchedzzzzword",null,null),6,new UsageCore.UsageRecorder());
            assertThat(result.chunks()).isEmpty();
            assertThat(((RetrievalTrace)result.trace()).nodes()).isEmpty();
            assertThat(((RetrievalTrace)result.trace()).fallbacks()).contains(FALLBACK);
            assertThat(((RetrievalTrace)result.trace()).blockedReason()).isNull();
        }
    }

    @Test void realCancellationAndWrappedInterruptionAreNotConvertedToLexicalSuccess() throws Exception {
        try(var f=new Fixture()) {
            // SocketTimeoutException也继承InterruptedIOException，但网络超时不是调用方取消。
            var timed=f.reader((input,usage,stage)->{throw new java.io.UncheckedIOException(new java.net.SocketTimeoutException("synthetic upstream timeout"));});
            var degraded=timed.searchPinned(timed.pin(""),new SearchInput("DNS timeout",null,null),6,new UsageCore.UsageRecorder());
            assertThat(degraded.chunks()).isNotEmpty();
            assertThat(((RetrievalTrace)degraded.trace()).fallbacks()).contains(FALLBACK);
            for(RuntimeException cancellation:List.of(new ModelCallCancelledException("synthetic cancelled"),
                    new CancellationException("synthetic cancelled"),new IllegalStateException(new InterruptedException("synthetic interruption")),
                    new IllegalStateException(new java.io.InterruptedIOException("synthetic interrupted IO")))) {
                var reader=f.reader((input,usage,stage)->{throw cancellation;});
                assertThatThrownBy(()->reader.searchPinned(reader.pin(""),new SearchInput("DNS interrupted "+cancellation.getClass().getSimpleName(),null,null),6,new UsageCore.UsageRecorder()))
                        .isSameAs(cancellation);
            }
        }
    }
}
