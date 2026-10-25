package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import org.junit.jupiter.api.Test;

class WorkflowTrailingJsonTest {
    @Test void rejectsEntireTrailingDocumentWithFrozenPythonErrorPosition() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var cases = json.readTree(getClass().getResourceAsStream("/migration/J4-02/json-trailing-golden.json"));
        assertThat(cases.size()).isEqualTo(4);
        for (var item : cases) {
            assertThatThrownBy(() -> new WorkflowBody(item.path("raw").asText()))
                    .isInstanceOfSatisfying(ApiDetailException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(422);
                        com.fasterxml.jackson.databind.JsonNode actual = json.valueToTree(error.detail());
                        assertThat(actual).isEqualTo(item.path("detail"));
                    });
        }
    }
    @Test void allowsOnlyLegalTrailingWhitespace() {
        var body = new WorkflowBody("{\"title\":\"DNS😀\"} \r\n\t");
        assertThat(body.string("title", null, false, 1, 200)).isEqualTo("DNS😀");
        body.finish();
    }
}
