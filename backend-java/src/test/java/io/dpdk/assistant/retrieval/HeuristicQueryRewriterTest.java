package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 启发式改写器对照冻结 Python（golden.rewritten 由真实
 * HeuristicLLMQueryRewriter 在无 LLM 路径生成）。
 */
class HeuristicQueryRewriterTest {

    private final HeuristicQueryRewriter rewriter = new HeuristicQueryRewriter();

    @Test
    void goldenRewritesMatchPython() {
        JsonNode golden = Fixtures.load("golden-retrieval.json");
        for (JsonNode c : golden.withArray("cases")) {
            if (!c.path("pinned").asBoolean(false)) {
                continue;
            }
            String query = c.get("query").asText();
            String expected = c.get("rewritten").asText();
            assertThat(rewriter.rewrite(query))
                    .as("case %s 改写串", c.get("case_id").asText())
                    .isEqualTo(expected);
        }
    }

    @Test
    void shortAsciiQueryPassesThrough() {
        assertThat(rewriter.rewrite("smtp state machine")).isEqualTo("smtp state machine");
        assertThat(rewriter.rewrite("")).isEmpty();
    }

    @Test
    void nfcAndNfdQueriesAreDistinctInputs() {
        // 两者都无关键词命中且非短 ASCII（é / e+U+0301 > U+2000? 否 —— é = U+00E9 < U+2000）。
        // NFC "café" 全部字符 <= U+2000 且长度 < 60 → 直通。
        String nfc = "café";
        String nfd = "café";
        assertThat(nfc.codePointCount(0, nfc.length())).isEqualTo(4);
        assertThat(nfd.codePointCount(0, nfd.length())).isEqualTo(5);
        assertThat(rewriter.rewrite(nfc)).isEqualTo(nfc);
        assertThat(rewriter.rewrite(nfd)).isEqualTo(nfd);
    }

    @Test
    void conceptualQueryKeepsArchitectureShape() {
        // “什么是”走概念分支：命中关键词 → "<关键词> system architecture"。
        assertThat(rewriter.rewrite("什么是流表")).isEqualTo("流表 system architecture");
    }

    @Test
    void llmLegUsedOnlyWhenHeuristicMisses() {
        AtomicInteger calls = new AtomicInteger();
        HeuristicQueryRewriter withLlm = new HeuristicQueryRewriter((sys, user) -> {
            calls.incrementAndGet();
            return "llm rewritten keywords";
        });
        // 启发式命中 → 零 LLM 调用。
        assertThat(withLlm.rewrite("证书 怎么提取")).isEqualTo("证书 cert_extractor.c cert_plugin protocol detection can_handle process");
        assertThat(calls.get()).isZero();
        // 启发式等于原文（含 CJK、无关键词）→ 走 LLM。
        assertThat(withLlm.rewrite("某某不存在的概念解析")).isEqualTo("llm rewritten keywords");
        assertThat(calls.get()).isEqualTo(1);
        // LLM 输出超长 → 回退启发式（原文）。
        HeuristicQueryRewriter verbose = new HeuristicQueryRewriter((sys, user) -> "x".repeat(301));
        assertThat(verbose.rewrite("某某不存在的概念解析")).isEqualTo("某某不存在的概念解析");
        // LLM 抛异常 → 回退启发式。
        HeuristicQueryRewriter failing = new HeuristicQueryRewriter((sys, user) -> {
            throw new RuntimeException("upstream 401");
        });
        assertThat(failing.rewrite("某某不存在的概念解析")).isEqualTo("某某不存在的概念解析");
    }

    @Test
    void followUpResolutionUsesContextWhenLlmPresent() {
        // 两次 LLM 调用：追问消解 → “独立的当前问题”；其本身含 CJK 且无关键词命中
        // → 再走关键词改写 LLM 腿。
        HeuristicQueryRewriter withLlm = new HeuristicQueryRewriter((sys, user) ->
                user.startsWith("历史对话") ? "独立的当前问题" : "llm rewritten keywords");
        String out = withLlm.rewrite("它怎么实现", "历史对话：流表定义……");
        assertThat(out).isEqualTo("llm rewritten keywords");
        // 无 LLM：追问消解失败 → 原文（“它”会命中 FOLLOW_UP，但解析失败回退，
        // 随后启发式无命中且 LLM 缺席 → 原文）。
        assertThat(rewriter.rewrite("它怎么实现", "历史对话")).isEqualTo("它怎么实现");
    }

    @Test
    void ftsQueryAndFragmentsMatchFrozenPython() {
        JsonNode golden = Fixtures.load("golden-retrieval.json");
        for (JsonNode c : golden.withArray("cases")) {
            if (!c.path("pinned").asBoolean(false)) {
                continue;
            }
            String query = c.get("query").asText();
            String rewritten = c.get("rewritten").asText();
            String ftsRewritten = RetrievalText.buildFtsQuery(rewritten);
            assertThat(ftsRewritten == null ? "" : ftsRewritten)
                    .as("case %s 改写 FTS 串", c.get("case_id").asText())
                    .isEqualTo(c.get("fts_query_rewritten").asText());
            String ftsOriginal = RetrievalText.buildFtsQuery(query);
            assertThat(ftsOriginal == null ? "" : ftsOriginal)
                    .isEqualTo(c.get("fts_query_original").asText());
            assertThat(RetrievalText.pathFragments(query, rewritten))
                    .as("case %s 路径片段", c.get("case_id").asText())
                    .containsExactlyElementsOf(
                            jsonStrings(c.get("path_fragments")));
        }
    }

    static List<String> jsonStrings(JsonNode array) {
        return Fixtures.mapper().convertValue(array,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<String>>() {
                });
    }
}
