package io.dpdk.assistant.chat.poc;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.contracts.chat.Citation;
import io.dpdk.assistant.contracts.model.ChatInput;
import io.dpdk.assistant.contracts.model.ChatModelPort;
import io.dpdk.assistant.contracts.model.ChatResult;
import io.dpdk.assistant.contracts.retrieval.ReleaseReader;
import io.dpdk.assistant.contracts.retrieval.ReleaseSnapshot;
import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.contracts.retrieval.SearchResult;
import io.dpdk.assistant.contracts.retrieval.SourceChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * J1-04 POC 裁决与引用绑定测试（mock 检索/模型；全链路集成测试待 J1-02/J1-03 集成后补）。
 * 引用黄金值取自 fixture p0-v2 的 citation-six-same-file 用例（同文件 6 片段保序、不截断）。
 */
class MinimalQaSliceTest {

    private static final List<RetrievedChunk> SIX = List.of(
            chunk("li_six_0", 1.0), chunk("li_six_1", 0.9), chunk("li_six_2", 0.8),
            chunk("li_six_3", 0.7), chunk("li_six_4", 0.6), chunk("li_six_5", 0.5));

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(id, "text-" + id, score);
    }

    private static ReleaseReader reader(List<RetrievedChunk> chunks) {
        return new ReleaseReader() {
            @Override
            public ReleaseSnapshot pin(String releaseId) {
                return new ReleaseSnapshot(releaseId);
            }

            @Override
            public SearchResult searchPinned(ReleaseSnapshot snapshot, SearchInput input) {
                return new SearchResult(chunks, null);
            }

            @Override
            public Optional<SourceChunk> historical(String engine, String chunkId) {
                return Optional.empty();
            }
        };
    }

    private static ChatModelPort model(String content) {
        return new ChatModelPort() {
            @Override
            public ChatResult complete(ChatInput input) {
                return new ChatResult(content, null);
            }

            @Override
            public java.util.concurrent.Flow.Publisher<io.dpdk.assistant.contracts.model.ModelDelta> stream(
                    ChatInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void answeredKeepsAllSixCitationsInOrder() {
        MinimalQaSlice slice = new MinimalQaSlice(reader(SIX), model("答案 [1][6]"));
        PocAnswer out = slice.ask("rel-1", "q");
        assertThat(out.answerStatus()).isEqualTo("answered");
        assertThat(out.citations()).hasSize(6);
        // 与黄金用例一致：顺序=证据编号，score=Python round4。
        double[] expected = {1.0, 0.9, 0.8, 0.7, 0.6, 0.5};
        for (int i = 0; i < 6; i++) {
            Citation c = out.citations().get(i);
            assertThat(c.chunkId()).isEqualTo("li_six_" + i);
            assertThat(c.rank()).isEqualTo(i + 1);
            assertThat(c.score()).isEqualTo(expected[i]);
            assertThat(c.engine()).isEqualTo("li");
        }
    }

    @Test
    void emptyAnswerFailsWithEmptyAnswer() {
        MinimalQaSlice slice = new MinimalQaSlice(reader(SIX), model("   "));
        assertThat(slice.ask("rel", "q")).extracting(PocAnswer::failureCode).isEqualTo("empty_answer");
    }

    @Test
    void outOfRangeRefFailsWithInvalidCitation() {
        MinimalQaSlice slice = new MinimalQaSlice(reader(SIX), model("答 [7]"));
        assertThat(slice.ask("rel", "q").failureCode()).isEqualTo("invalid_citation");
        MinimalQaSlice zero = new MinimalQaSlice(reader(SIX), model("答 [0]"));
        assertThat(zero.ask("rel", "q").failureCode()).isEqualTo("invalid_citation");
    }

    @Test
    void noChunksFailsWithNoEvidence() {
        MinimalQaSlice slice = new MinimalQaSlice(reader(List.of()), model("答"));
        assertThat(slice.ask("rel", "q").failureCode()).isEqualTo("no_evidence");
    }

    @Test
    void chunksWithoutRefsFailsWithMissingCitation() {
        MinimalQaSlice slice = new MinimalQaSlice(reader(SIX), model("没有引用的回答"));
        assertThat(slice.ask("rel", "q").failureCode()).isEqualTo("missing_citation");
    }

    @Test
    void retrievalFailureIsControlledSystemError() {
        ReleaseReader broken = new ReleaseReader() {
            @Override
            public ReleaseSnapshot pin(String releaseId) {
                throw new IllegalStateException("no active release");
            }

            @Override
            public SearchResult searchPinned(ReleaseSnapshot snapshot, SearchInput input) {
                throw new IllegalStateException();
            }

            @Override
            public Optional<SourceChunk> historical(String engine, String chunkId) {
                return Optional.empty();
            }
        };
        assertThat(new MinimalQaSlice(broken, model("x")).ask("rel", "q").failureCode())
                .isEqualTo("system_error");
    }

    @Test
    void modelFailureIsControlledSystemError() {
        ChatModelPort broken = new ChatModelPort() {
            @Override
            public ChatResult complete(ChatInput input) {
                throw new IllegalStateException("upstream down");
            }

            @Override
            public java.util.concurrent.Flow.Publisher<io.dpdk.assistant.contracts.model.ModelDelta> stream(
                    ChatInput input) {
                throw new IllegalStateException();
            }
        };
        assertThat(new MinimalQaSlice(reader(SIX), broken).ask("rel", "q").failureCode())
                .isEqualTo("system_error");
    }

    @Test
    void pythonRound4UsesHalfEvenOnExactDouble() {
        // Python: round(2.675, 4)=2.675；round(0.123455, 4) 依二进制精确值 HALF_EVEN。
        assertThat(OrderedCitationBinder.pythonRound4(2.675)).isEqualTo(2.675);
        assertThat(OrderedCitationBinder.pythonRound4(1.01999999999999998)).isEqualTo(1.02);
    }

    @Test
    void refExtractionHandlesMultipleAndDistractors() {
        assertThat(MinimalQaSlice.extractRefs("a [1] b [2][10] c [3]")).containsExactly(1, 2, 10, 3);
        assertThat(MinimalQaSlice.extractRefs("数组下标 x[1] 与 [42]")).containsExactly(1, 42);
    }
}
