package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class WorkflowTrailingJsonPgTest {
    @Test void malformedWholeDocumentsReturn422WithoutKnowledgeOrRevisionWrites() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var cases = json.readTree(getClass().getResourceAsStream("/migration/J4-02/json-trailing-golden.json"));
        try (var h = WorkflowTestHarness.create("strictjson")) {
            var mvc = h.mvc();
            for (var item : cases) {
                var result = mvc.perform(post("/api/rag/knowledge")
                        .header("Authorization", "Bearer " + h.tokens.get("teacher"))
                        .contentType("application/json").content(item.path("raw").asText()))
                        .andExpect(status().isUnprocessableEntity()).andReturn();
                assertThat(json.readTree(result.getResponse().getContentAsString()).path("detail"))
                        .isEqualTo(item.path("detail"));
                assertThat(h.task.countRows("rag_knowledge", null)).isZero();
                assertThat(h.task.countRows("rag_knowledge_revisions", null)).isZero();
            }
            String valid = "{\"title\":\"DNS😀\",\"body\":\"公开教学材料\",\"status\":\"published\",\"shared_confirmed\":true}";
            var created = mvc.perform(post("/api/rag/knowledge")
                    .header("Authorization", "Bearer " + h.tokens.get("teacher"))
                    .contentType("application/json").content(valid + " \n\t"))
                    .andExpect(status().isOk()).andReturn();
            String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(h.task.countRows("rag_knowledge", null)).isEqualTo(1);
            assertThat(h.task.countRows("rag_knowledge_revisions", null)).isEqualTo(1);
            mvc.perform(patch("/api/rag/knowledge/" + id)
                    .header("Authorization", "Bearer " + h.tokens.get("teacher"))
                    .contentType("application/json").content(valid.substring(0, valid.length() - 1) + ",\"revision\":1}{}"))
                    .andExpect(status().isUnprocessableEntity());
            assertThat(h.task.countRows("rag_knowledge", null)).isEqualTo(1);
            assertThat(h.task.countRows("rag_knowledge_revisions", null)).isEqualTo(1);
        }
    }
}
