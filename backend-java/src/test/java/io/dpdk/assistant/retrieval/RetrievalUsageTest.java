package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.model.ModelUsageLedger;
import io.dpdk.assistant.usage.UsageCore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 请求级真实检索计量；不连PG，走与searchPinned相同的embedding/rewriter调用面。 */
class RetrievalUsageTest {
    private static String endpoint() { return "http://127.0.0.1/" + UUID.randomUUID(); }
    private static Vector1024 vector(float first) { float[] v = new float[1024]; v[0] = first; return new Vector1024(v); }

    @Test void localThenCrossInstanceCacheHitHaveNullTokensAndZeroCalls() {
        String base = endpoint();
        var first = new HeuristicQueryRewriter(null, base, "qwen3.8-flash");
        var second = new HeuristicQueryRewriter(null, base, "qwen3.8-flash");
        var a = new UsageCore.UsageRecorder("a", "session-a");
        var b = new UsageCore.UsageRecorder("b", "session-b");
        assertThat(first.rewrite("dns", null, a)).isEqualTo("dns");
        assertThat(second.rewrite("dns", null, b)).isEqualTo("dns");
        assertThat(a.entries()).containsExactly(new UsageCore.UsageEntry("llm", "rewrite", "local", "qwen3.8-flash", null, null, null, 0, null));
        assertThat(b.entries()).containsExactly(new UsageCore.UsageEntry("llm", "rewrite", "cache_hit", "qwen3.8-flash", null, null, null, 0, null));
        assertThat(a.subject()).isEqualTo("a"); assertThat(b.subject()).isEqualTo("b");
    }

    private PgReleaseReader reader(String base, PgReleaseReader.QueryEmbeddingClient client) {
        return new PgReleaseReader(null, RetrievalSettings.defaults(),
                new EmbeddingConfig("text-embedding-v4", base, 1024, "bounded-lines-v2"),
                new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(), null, client);
    }

    @Test void actualEmbeddingUsageAndRetryCountBridgeThenCachedRequestIsZero() {
        var calls = new AtomicInteger();
        var reader = reader(endpoint(), (text, usage, stage) -> {
            calls.incrementAndGet();
            usage.recordLlm(stage, "text-embedding-v4", Map.of("prompt_tokens", 12, "total_tokens", 12), 7L, "embedding", 2);
            return List.of(vector(1));
        });
        var first = new UsageCore.UsageRecorder(); var second = new UsageCore.UsageRecorder();
        assertThat(reader.queryEmbedding("dns", "dns", first)).hasSize(1024);
        reader.queryEmbedding("dns", "dns", second);
        assertThat(calls).hasValue(1);
        assertThat(first.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "upstream", "text-embedding-v4", 12, null, 12, 2, 7));
        assertThat(second.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "cache_hit", "text-embedding-v4", null, null, null, 0, null));
    }

    @Test void embeddingFailureRetainsUnknownAttemptsAndDoesNotPopulateCache() {
        var attempts = new AtomicInteger();
        var reader = reader(endpoint(), (text, usage, stage) -> {
            int n = attempts.incrementAndGet();
            usage.recordLlm(stage, "text-embedding-v4", null, 3L, "embedding", n == 1 ? 3 : 1);
            if (n == 1) throw new IllegalStateException("synthetic embedding failure");
            return List.of(vector(1));
        });
        var failed = new UsageCore.UsageRecorder();
        assertThatThrownBy(() -> reader.queryEmbedding("dns", "dns", failed)).isInstanceOf(IllegalStateException.class);
        assertThat(failed.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "unknown", "text-embedding-v4", null, null, null, 3, 3));
        var retry = new UsageCore.UsageRecorder(); reader.queryEmbedding("dns", "dns", retry);
        assertThat(attempts).hasValue(2); assertThat(retry.entries()).hasSize(1);
        assertThat(retry.entries().getFirst().callCount()).isEqualTo(1);
    }

    @Test void concurrentCacheJoinDoesNotCopyOwnersTokensOrIdentity() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var reader = reader(endpoint(), (text, usage, stage) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            usage.recordLlm(stage, "text-embedding-v4", Map.of("total_tokens", 11), 1L, "embedding", 1);
            return List.of(vector(1));
        });
        var owner = new UsageCore.UsageRecorder("owner", "o"); var joined = new UsageCore.UsageRecorder("joined", "j");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> reader.queryEmbedding("dns", "dns", owner));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var b = workers.submit(() -> reader.queryEmbedding("dns", "dns", joined));
            release.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        assertThat(calls).hasValue(1);
        assertThat(owner.entries().getFirst().totalTokens()).isEqualTo(11);
        assertThat(joined.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "cache_hit", "text-embedding-v4", null, null, null, 0, null));
        assertThat(joined.subject()).isEqualTo("joined");
    }

    @Test void fixedVectorBoundaryDoesNotInventUpstreamButRealHitIsAudited() {
        var calls = new AtomicInteger();
        var reader = reader(endpoint(), (text, ledger, stage) -> {
            calls.incrementAndGet(); return List.of(vector(1)); // 与冻结Python固定provider同位：无usage能力。
        });
        var first = new UsageCore.UsageRecorder();
        reader.queryEmbedding("dns", "dns", first);
        assertThat(first.entries()).isEmpty();
        var second = new UsageCore.UsageRecorder();
        reader.queryEmbedding("dns", "dns", second);
        assertThat(calls).hasValue(1);
        assertThat(second.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "cache_hit", "text-embedding-v4", null, null, null, 0, null));
    }

    @Test void unmeteredVerificationCanWarmCacheWithoutBorrowingLaterRequestRecorder() {
        var calls = new AtomicInteger();
        var reader = reader(endpoint(), (text, ledger, stage) -> {
            calls.incrementAndGet();
            assertThat(ledger).isNull(); // verify源没有recorder；不能追写到下一用户账上。
            return List.of(vector(1));
        });
        reader.queryEmbedding("dns", "dns", null);
        var chat = new UsageCore.UsageRecorder("chat-user", "chat-session");
        reader.queryEmbedding("dns", "dns", chat);
        assertThat(calls).hasValue(1);
        assertThat(chat.entries().getFirst().usageSource()).isEqualTo("cache_hit");
        assertThat(chat.entries().getFirst().callCount()).isZero();
        assertThat(chat.entries().getFirst().totalTokens()).isNull();
    }

    @Test void rewriteUsesActualUsageAndExactStageThenSkipsCachedUpstream() {
        var calls = new AtomicInteger();
        QueryRewritePort.LlmClient client = new QueryRewritePort.LlmClient() {
            public String generate(String system, String user) { throw new AssertionError("富入口必须传账本"); }
            public String generate(String system, String user, ModelUsageLedger ledger, String stage) {
                calls.incrementAndGet(); assertThat(stage).isEqualTo("rewrite");
                ledger.recordLlm(stage, "qwen3.8-flash", Map.of("prompt_tokens", 7, "completion_tokens", 2, "total_tokens", 9), 5L, "llm", 2);
                return "rewritten keywords";
            }
        };
        var rewrite = new HeuristicQueryRewriter(client, endpoint(), "qwen3.8-flash");
        var a = new UsageCore.UsageRecorder(); var b = new UsageCore.UsageRecorder();
        assertThat(rewrite.rewrite("某某不存在的概念解析", null, a)).isEqualTo("rewritten keywords");
        rewrite.rewrite("某某不存在的概念解析", null, b);
        assertThat(calls).hasValue(1);
        assertThat(a.entries()).containsExactly(new UsageCore.UsageEntry("llm", "rewrite", "upstream", "qwen3.8-flash", 7, 2, 9, 2, 5));
        assertThat(b.entries()).containsExactly(new UsageCore.UsageEntry("llm", "rewrite", "cache_hit", "qwen3.8-flash", null, null, null, 0, null));
    }

    @Test void failedRewriteKeepsActualUnknownCallsAndCachesReturnedHeuristicFallback() {
        var calls = new AtomicInteger();
        QueryRewritePort.LlmClient client = new QueryRewritePort.LlmClient() {
            public String generate(String system, String user) { throw new AssertionError(); }
            public String generate(String system, String user, ModelUsageLedger ledger, String stage) {
                calls.incrementAndGet(); ledger.recordLlm(stage, "qwen3.8-flash", null, 4L, "llm", 3);
                throw new IllegalStateException("synthetic rewrite failure");
            }
        };
        var rewrite = new HeuristicQueryRewriter(client, endpoint(), "qwen3.8-flash");
        var failed = new UsageCore.UsageRecorder();
        assertThat(rewrite.rewrite("某某不存在的概念解析", null, failed)).isEqualTo("某某不存在的概念解析");
        assertThat(failed.entries()).containsExactly(new UsageCore.UsageEntry("llm", "rewrite", "unknown", "qwen3.8-flash", null, null, null, 3, 4));
        var next = new UsageCore.UsageRecorder(); rewrite.rewrite("某某不存在的概念解析", null, next);
        assertThat(calls).hasValue(1);
        assertThat(next.entries().getFirst().usageSource()).isEqualTo("cache_hit");
    }

    @Test void followupIsActualUncachedCallWhileResolvedRewriteCanHitCache() {
        var stages = new CopyOnWriteArrayList<String>();
        QueryRewritePort.LlmClient client = new QueryRewritePort.LlmClient() {
            public String generate(String system, String user) { throw new AssertionError(); }
            public String generate(String system, String user, ModelUsageLedger ledger, String stage) {
                stages.add(stage); ledger.recordLlm(stage, "qwen3.8-flash", Map.of("total_tokens", 6), null, "llm", 1);
                return "dns";
            }
        };
        var rewrite = new HeuristicQueryRewriter(client, endpoint(), "qwen3.8-flash");
        var first = new UsageCore.UsageRecorder(); var second = new UsageCore.UsageRecorder();
        rewrite.rewrite("它怎么实现", "真实前轮上下文", first);
        rewrite.rewrite("它怎么实现", "真实前轮上下文", second);
        assertThat(stages).containsExactly("followup", "followup");
        assertThat(first.entries().stream().map(UsageCore.UsageEntry::usageSource)).containsExactly("upstream", "local");
        assertThat(second.entries().stream().map(UsageCore.UsageEntry::usageSource)).containsExactly("upstream", "cache_hit");
    }

    @Test void endpointModelAndActualEmbeddingQueryAreAllCacheKeyParts() {
        String base = endpoint(); var calls = new AtomicInteger();
        PgReleaseReader.QueryEmbeddingClient boundary = (text, ledger, stage) -> { calls.incrementAndGet(); return List.of(vector(1)); };
        var first = reader(base, boundary); first.queryEmbedding("q", "rewritten", null);
        reader(base, boundary).queryEmbedding("q", "rewritten", new UsageCore.UsageRecorder());
        reader(base+"/different", boundary).queryEmbedding("q", "rewritten", null);
        first.queryEmbedding("q", "other", null);
        var anotherModel = new PgReleaseReader(null, RetrievalSettings.defaults(),
                new EmbeddingConfig("another-model", base, 1024, "bounded-lines-v2"),
                new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(), null, boundary);
        anotherModel.queryEmbedding("q", "rewritten", null);
        assertThat(calls).hasValue(4);
    }
}
