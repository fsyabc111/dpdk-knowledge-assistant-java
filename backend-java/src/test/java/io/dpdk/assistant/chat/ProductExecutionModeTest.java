package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;

import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.agent.tools.AgentToolContext;

import org.junit.jupiter.api.Test;

import java.util.*;

class ProductExecutionModeTest {
    @Test
    void productEmptyAnswerReachesDeterministicValidatorWhileLegacyAgentRetainsFallback() {
        AgentOrchestrator orchestrator =
                new AgentOrchestrator(
                        (m, t) -> AgentChatModel.AgentReply.text(""), AgentSettings.defaults());
        var strict =
                orchestrator.runProduct(
                        "student",
                        List.of("viewer"),
                        "s",
                        "Q",
                        null,
                        null,
                        null,
                        null,
                        null,
                        AgentToolContext.builder().build());
        assertThat(strict.answer).isEmpty();
        assertThat(strict.fallback).isFalse();
        var verdict =
                AnswerValidator.validate(
                        "Q",
                        strict.answer,
                        Map.of("chunks", List.of(), "tools", List.of()),
                        null,
                        java.time.Duration.ofSeconds(1));
        assertThat(verdict.reason()).isEqualTo("empty_answer");
        var legacy =
                orchestrator.run(
                        "student",
                        List.of("viewer"),
                        "s",
                        "Q",
                        null,
                        null,
                        null,
                        null,
                        null,
                        AgentToolContext.builder().build());
        assertThat(legacy.fallback).isTrue();
    }

    @Test
    void productGenerationExceptionAndComp003AreNeverConvertedToAgentFallbackText() {
        for (RuntimeException failure :
                List.of(
                        new IllegalStateException("upstream failed"),
                        new io.dpdk.assistant.agent.tools.HarnessResultUnknown())) {
            AgentOrchestrator orchestrator =
                    new AgentOrchestrator(
                            (m, t) -> {
                                throw failure;
                            },
                            AgentSettings.defaults());
            assertThatThrownBy(
                            () ->
                                    orchestrator.runProduct(
                                            "student",
                                            List.of("viewer"),
                                            "s",
                                            "Q",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            AgentToolContext.builder().build()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(failure.getMessage());
        }
    }
}
