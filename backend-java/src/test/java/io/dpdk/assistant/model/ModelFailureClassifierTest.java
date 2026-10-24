package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** 失败分类与重试延迟矩阵（对齐 is_retriable_llm_error / llm_retry_delay_seconds）。 */
class ModelFailureClassifierTest {

    @Test
    void timeoutAndNetworkErrorsAreRetriable() {
        assertThat(ModelFailureClassifier.isRetriable(
                new ResourceAccessException("Read timeout")))
                .isTrue();
    }

    @Test
    void statusMatrixMatchesSource() {
        // 429 与 5xx 可重试；4xx 其余不可。
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(429, "", null))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(500, "", null))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(503, "", null))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(400, "", null))).isFalse();
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(401, "", null))).isFalse();
        assertThat(ModelFailureClassifier.isRetriable(new ModelHttpException(404, "", null))).isFalse();
    }

    @Test
    void statusFoundThroughCauseChain() {
        RuntimeException wrapped = new RuntimeException("wrapper",
                new ModelHttpException(502, "", null));
        assertThat(ModelFailureClassifier.isRetriable(wrapped)).isTrue();
    }

    @Test
    void messageMarkersAreRetriable() {
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("Connection reset by peer"))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("Request Timed Out"))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("service temporarily unavailable"))).isTrue();
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("connection error: refused"))).isTrue();
    }

    @Test
    void ordinaryErrorsAreNotRetriable() {
        assertThat(ModelFailureClassifier.isRetriable(new ModelProtocolException("畸形响应"))).isFalse();
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("boom"))).isFalse();
        assertThat(ModelFailureClassifier.isRetriable(new RuntimeException("bad json"))).isFalse();
    }

    @Test
    void chatRetryDelayIsExponentialWithoutJitter() {
        // base * 2**max(attempt-1, 0)，attempt 从 1 起。
        assertThat(ModelFailureClassifier.retryDelaySeconds(1, 0.001)).isEqualTo(0.001);
        assertThat(ModelFailureClassifier.retryDelaySeconds(2, 0.001)).isEqualTo(0.002);
        assertThat(ModelFailureClassifier.retryDelaySeconds(3, 0.001)).isEqualTo(0.004);
        assertThat(ModelFailureClassifier.retryDelaySeconds(0, 2.0)).isEqualTo(2.0);
    }

    @Test
    void embeddingRetryAfterHeaderTakesPrecedence() {
        assertThat(ModelFailureClassifier.embeddingRetryDelaySeconds(0, 5.0, 0.02, 0.9))
                .isEqualTo(0.02);
        // Retry-After ≤0 / 缺失：指数退避 + 抖动。
        assertThat(ModelFailureClassifier.embeddingRetryDelaySeconds(0, 5.0, 0.0, 0.5))
                .isEqualTo(5.0 + 0.5 * Math.min(0.5, 5.0 * 0.25));
        assertThat(ModelFailureClassifier.embeddingRetryDelaySeconds(1, 1.0, null, 0.0))
                .isEqualTo(2.0);
    }

    @Test
    void embeddingJitterBoundIsCapped() {
        // jitter bound = min(0.5, delay*0.25)：delay 大时封顶 0.5。
        double withFullJitter = ModelFailureClassifier.embeddingRetryDelaySeconds(5, 10.0, null, 1.0);
        assertThat(withFullJitter).isEqualTo(10.0 * 32 + 0.5);
    }
}
