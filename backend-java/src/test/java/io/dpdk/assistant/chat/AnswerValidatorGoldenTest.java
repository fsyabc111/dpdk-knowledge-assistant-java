package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import io.dpdk.assistant.agent.port.KbChunk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

class AnswerValidatorGoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void allFrozenPythonVerdictBranchesMatch() throws Exception {
        var root =
                JSON.readTree(
                        getClass().getResourceAsStream("/migration/J3-03/validation-golden.json"));
        int count = 0;
        for (var c : root.path("validation")) {
            Map<String, Object> evidence =
                    JSON.convertValue(
                            c.path("evidence"),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            AnswerValidator.Judge judge =
                    c.path("judge").isNull()
                            ? null
                            : (q, a, e, t) ->
                                    JSON.readValue(
                                            c.path("judge").asText(),
                                            new com.fasterxml.jackson.core.type
                                                    .TypeReference<>() {});
            var result =
                    AnswerValidator.validate(
                            c.path("question").asText(),
                            c.path("answer").asText(),
                            evidence,
                            judge,
                            Duration.ofSeconds(2));
            assertThat((JsonNode) JSON.valueToTree(result))
                    .as(c.path("id").asText())
                    .isEqualTo(c.path("expected"));
            count++;
        }
        assertThat(count).isEqualTo(20);
    }

    @Test
    void boundedEvidencePreservesAllPositionsAndMatchesPythonUnicodeBudgets() throws Exception {
        var root =
                JSON.readTree(
                        getClass().getResourceAsStream("/migration/J3-03/validation-golden.json"));
        for (var c : root.path("bound_evidence")) {
            List<KbChunk> chunks = new ArrayList<>();
            for (var n : c.path("evidence").path("chunks"))
                chunks.add(
                        new KbChunk(
                                "c" + n.path("ref").asInt(),
                                n.path("source").asText(),
                                "",
                                "",
                                n.path("content").asText(),
                                0));
            var actual = AnswerValidator.evidence(chunks, List.of(), c.path("limit").asInt());
            assertThat((JsonNode) JSON.valueToTree(actual))
                    .as("limit=%s", c.path("limit"))
                    .isEqualTo(c.path("expected"));
        }
    }

    @Test
    void enormousCitationIndexFailsClosedWithoutParserOverflow() {
        var result =
                AnswerValidator.validate(
                        "Q",
                        "answer [999999999999999999999999999999]",
                        Map.of("chunks", List.of(Map.of("content", "x")), "tools", List.of()),
                        null,
                        Duration.ofSeconds(1));
        assertThat(result.reason()).isEqualTo("invalid_citation");
    }
}
