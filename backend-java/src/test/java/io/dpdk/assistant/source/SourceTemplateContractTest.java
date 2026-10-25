package io.dpdk.assistant.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.session.J205TestHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-05 来源与模板契约（验收 C/F）：golden /sources 三例 + /plugin-template 三例
 * （模板逐字节 = golden）；行为：li_ 与历史 chunk 双路径、title 回落、缺失 404、
 * 模板字段/默认值/非法输入契约（空协议、非字符串、缺体、多余字段忽略）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class SourceTemplateContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static J205TestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("srctpl");
        h.seedUser("U20270000001", "[\"leader\"]", 10000, null);
        h.seedUser("U20270000002", "[\"admin\"]", 10000, "U20270000001");
        h.seedUser("U20270000011", "[\"viewer\"]", 10000, "U20270000002");
        h.insertKnowledgeChunk("doc_hist", "历史DNS", "p0-history", "synthetic/history.md",
                "历史", "历史固定来源", "{}");
        // 无 title 文档：title 回落 source_path。
        h.insertKnowledgeChunk("doc_notitle", null, "chunk_notitle1", "bare/source.md",
                "sec", "裸内容", "{}");
        h.insertLiNode("li_zzz0000001", "li 固定内容", "synthetic/li.md", "LI 文档", "li 小节");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void goldenSourcesFlow() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String token = h.login(mvc, "U20270000011");

        JsonNode source = json(mvc, get("/api/sources/p0-history")
                .header("Authorization", bearer(token)));
        assertThat(source).isEqualTo(observation(golden, "GET /api/sources/{chunk_id}::success")
                .get("body"));

        mvc.perform(get("/api/sources/p0-history"))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "GET /api/sources/{chunk_id}::unauthorized")
                                .get("body")));
        mvc.perform(get("/api/sources/p0-missing")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/sources/{chunk_id}::invalid-resource").get("body")));
    }

    @Test
    void goldenTemplateFlow() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String token = h.login(mvc, "U20270000001");

        JsonNode template = json(mvc, post("/api/plugin-template")
                .contentType("application/json")
                .content("{\"protocol\":\"dns\"}")
                .header("Authorization", bearer(token)));
        // 模板/解释/清单/引用逐字段（模板为逐字节 golden 文本）。
        assertThat(template).isEqualTo(observation(golden, "POST /api/plugin-template::success")
                .get("body"));

        mvc.perform(post("/api/plugin-template").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "POST /api/plugin-template::unauthorized")
                                .get("body")));
        mvc.perform(post("/api/plugin-template")
                        .contentType("application/json")
                        .content("{}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "POST /api/plugin-template::invalid-input")
                                .get("body")));
    }

    /* ------------------------------------------------------------------ */
    /* 行为                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void liChunkReadableAndLegacyTitleFallsBackToSourcePath() throws Exception {
        var mvc = h.newMvc();
        String token = bearer(h.login(mvc, "U20270000011"));

        JsonNode li = json(mvc, get("/api/sources/li_zzz0000001").header("Authorization", token));
        assertThat(li.get("chunk_id").asText()).isEqualTo("li_zzz0000001");
        assertThat(li.get("source_path").asText()).isEqualTo("synthetic/li.md");
        assertThat(li.get("title").asText()).isEqualTo("LI 文档");
        assertThat(li.get("section").asText()).isEqualTo("li 小节");
        assertThat(li.get("content").asText()).isEqualTo("li 固定内容");
        assertThat(li.get("metadata").get("source_path").asText()).isEqualTo("synthetic/li.md");

        // legacy 无 title：回落 source_path。
        JsonNode legacy = json(mvc, get("/api/sources/chunk_notitle1")
                .header("Authorization", token));
        assertThat(legacy.get("title").asText()).isEqualTo("bare/source.md");
        assertThat(legacy.get("section").asText()).isEqualTo("sec");

        // li_ 缺失同样 404。
        mvc.perform(get("/api/sources/li_missing0001").header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void templateContractDefaultsAndInvalidInputs() throws Exception {
        var mvc = h.newMvc();
        String token = bearer(h.login(mvc, "U20270000001"));

        // protocol 空白串 → strip 后取默认名 MyProtocol（symbol=myproto）。
        JsonNode defaulted = json(mvc, post("/api/plugin-template")
                .contentType("application/json")
                .content("{\"protocol\":\"   \"}")
                .header("Authorization", token));
        assertThat(defaulted.get("template").asText()).contains("myprotocol_init").contains(".name = \"MyProtocol\"");
        assertThat(defaulted.get("explanation").asText()).contains("MyProtocol");
        // 模板类型/难度/要求为可选默认（不改变输出），多余字段忽略。
        JsonNode plain = json(mvc, post("/api/plugin-template")
                .contentType("application/json")
                .content("{\"protocol\":\"dns\"}")
                .header("Authorization", token));
        JsonNode extra = json(mvc, post("/api/plugin-template")
                .contentType("application/json")
                .content("{\"protocol\":\"dns\",\"template_type\":\"other\",\"difficulty\":\"hard\","
                        + "\"requirements\":\"r\",\"unknown_field\":1}")
                .header("Authorization", token));
        assertThat(extra).isEqualTo(plain);

        // 非字符串 protocol → 422 string_type。
        mvc.perform(post("/api/plugin-template")
                        .contentType("application/json")
                        .content("{\"protocol\":123}")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> {
                    JsonNode detail = MAPPER.readTree(r.getResponse().getContentAsString())
                            .get("detail");
                    assertThat(detail.isArray()).isTrue();
                    assertThat(detail.get(0).get("type").asText()).isEqualTo("string_type");
                });
        // 空 body → 422 missing @ ["body"]。
        mvc.perform(post("/api/plugin-template")
                        .contentType("application/json")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> {
                    JsonNode detail = MAPPER.readTree(r.getResponse().getContentAsString())
                            .get("detail");
                    assertThat(detail.get(0).get("type").asText()).isEqualTo("missing");
                });
        // 非对象 body → model_attributes_type。
        mvc.perform(post("/api/plugin-template")
                        .contentType("application/json")
                        .content("[1,2]")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString())
                        .get("detail").get(0).get("type").asText())
                        .isEqualTo("model_attributes_type"));
        // 非法 JSON → json_invalid。
        mvc.perform(post("/api/plugin-template")
                        .contentType("application/json")
                        .content("{bad")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString())
                        .get("detail").get(0).get("type").asText()).isEqualTo("json_invalid"));
    }

    @Test
    void symbolNormalizationMatchesPythonIsalnumSemantics() {
        assertThat(io.dpdk.assistant.template.TemplateService.generate("dns").template())
                .contains("dns_init");
        // 非法字符 → "_"；去首尾 "_"；全非法 → myproto。
        assertThat(symbolOf("My Protocol!")).isEqualTo("my_protocol");
        assertThat(symbolOf("--!!")).isEqualTo("myproto");
        // 中文按 Unicode 字母归一（Python isalnum 同口径）。
        assertThat(symbolOf("协议DNS")).isEqualTo("协议dns");
    }

    private static String symbolOf(String name) {
        // 经生成文本反查太脆，直接调包内静态方法（测试同包可见性不适用 → 反射）。
        try {
            var method = io.dpdk.assistant.template.TemplateService.class
                    .getDeclaredMethod("symbolOf", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------------ */

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static JsonNode json(org.springframework.test.web.servlet.MockMvc mvc,
                                 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return MAPPER.readTree(mvc.perform(b).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode observation(JsonNode golden, String caseId) {
        for (JsonNode o : golden.get("observations")) {
            if (caseId.equals(o.get("case_id").asText())) {
                return o;
            }
        }
        throw new AssertionError("golden case not found: " + caseId);
    }
}
