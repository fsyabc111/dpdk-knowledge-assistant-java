package io.dpdk.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * J2-01 契约测试（验收 A/E 为主）：三模式矩阵 + /api/auth/* 端点对冻结 golden
 * （FIX/J2-01/golden-auth.json，提取自 p0-v2/python-golden.json）逐 observation
 * 断言。随机 token/时间按 J0-02 归一化规则映射后比对（映射登记于
 * evidence/J2-01/golden-diff.md）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AuthContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AuthTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AuthTestHarness.accountMode("contract");
        // golden 合成树：8 个登录主体（success-disabled 在登录时点尚未停用）。
        h.seedUser("U20270000001", "[\"leader\"]", 0, true, true, "U20270000001");
        h.seedUser("U20270000002", "[\"admin\"]", 0, true, true, "U20270000002");
        h.seedUser("U20270000003", "[\"admin\"]", 0, true, true, "U20270000003");
        h.seedUser("U20270000011", "[\"viewer\"]", 50, true, true, "U20270000011");
        h.seedUser("U20270000021", "[\"viewer\"]", 50, true, true, "U20270000021");
        h.seedUser("U20270000031", "[\"viewer\"]", 50, true, true, "U20270000031");
        h.seedUser("U20270000041", "[\"viewer\"]", 50, true, true, "U20270000041");
        h.seedUser("U20270000051", "[\"viewer\"]", 50, true, true, "U20270000051");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden：POST /api/auth/login::success-*（8 条）                       */
    /* ------------------------------------------------------------------ */

    @Test
    void goldenLoginSuccessObservations() throws Exception {
        JsonNode golden = loadGolden();
        List<String> expectedSubjects = List.of(
                "U20270000001", "U20270000002", "U20270000003", "U20270000011",
                "U20270000021", "U20270000031", "U20270000041", "U20270000051");
        var logins = new java.util.ArrayList<JsonNode>();
        for (JsonNode obs : golden.withArray("observations")) {
            if ("POST /api/auth/login".equals(obs.path("route_id").asText())
                    && obs.path("case_id").asText().startsWith("POST /api/auth/login::success")) {
                logins.add(obs);
            }
        }
        assertThat(logins).hasSize(8);
        for (String id : expectedSubjects) {
            h.resetToInitialPassword(id); // 隔离其他用例对口令的变更
        }
        MockMvc mvc = h.newMvc();
        Map<String, String> passwords = new LinkedHashMap<>();
        for (String id : expectedSubjects) {
            passwords.put(id, id); // 初始密码 = 学号（golden <synthetic-credential> 归一化）
        }
        for (JsonNode obs : logins) {
            String username = obs.path("request").path("input").path("username").asText();
            MvcResult result = mvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content(MAPPER.writeValueAsString(Map.of(
                                    "username", username, "password", passwords.get(username)))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/json"))
                    .andReturn();
            JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
            JsonNode expected = obs.path("body");
            // 逐字段：subject/roles/must_change_password 精确；token/expires_at 归一化映射。
            assertThat(body.path("subject").asText()).isEqualTo(expected.path("subject").asText());
            assertThat(body.path("roles").toString()).isEqualTo(expected.path("roles").toString());
            assertThat(body.path("must_change_password").asBoolean())
                    .isEqualTo(expected.path("must_change_password").asBoolean());
            // token：43 字符 base64url（token_urlsafe(32) 归一化映射 <identity:N>）。
            assertThat(body.path("token").asText()).matches("^[A-Za-z0-9_-]{43}$");
            // expires_at：isoformat +00:00、微秒非零时 6 位小数（pythonIso 规则：零微秒无小数）、
            // = 签发 + 7 天。golden 的 .000020+ 是 oracle 顺序微秒，本侧为受控时钟的
            // 等价严格递增微秒序列（签发在同一受控墙钟上）。
            String expiresAt = body.path("expires_at").asText();
            assertThat(expiresAt).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$");
            Instant expiresInstant = OffsetDateTime.parse(expiresAt).toInstant();
            Instant issued = expiresInstant.minusSeconds(7 * 24 * 3600);
            // 签发时刻 = 受控墙钟（04:00:00Z）起严格递增微秒序列：偏移量在微秒级有限范围内。
            long microsFromWall = java.time.Duration.between(
                    h.clock.nowUtc().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), issued).toNanos() / 1_000;
            assertThat(microsFromWall).isBetween(0L, 60_000_000L);
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden：422 invalid-input（4 条，JSON 值级逐字段相等）                 */
    /* ------------------------------------------------------------------ */

    @Test
    void goldenInvalidInputObservations() throws Exception {
        JsonNode golden = loadGolden();
        for (JsonNode obs : golden.withArray("observations")) {
            String route = obs.path("route_id").asText();
            if (!obs.path("case_id").asText().endsWith("::invalid-input")) {
                continue;
            }
            // golden 的 invalid-input 带 actor（change-password 为已认证主体），按需附 token。
            String path = obs.path("request").path("path_template").asText();
            var requestBuilder = post(path).contentType("application/json").content("{}");
            if ("/api/auth/change-password".equals(path)) {
                requestBuilder = requestBuilder.header("Authorization",
                        "Bearer " + login("U20270000011", "U20270000011"));
            }
            MvcResult result = h.newMvc().perform(requestBuilder)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(header().string("Content-Type", "application/json"))
                    .andReturn();
            // golden 文件按键排序转储；比较用 JsonNode 等值（对象键序不敏感、数组序敏感）。
            JsonNode actual = MAPPER.readTree(result.getResponse().getContentAsString());
            assertThat(actual).as("case %s", obs.path("case_id").asText()).isEqualTo(obs.path("body"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden：401 unauthorized（2 条，字节精确）                             */
    /* ------------------------------------------------------------------ */

    @Test
    void goldenUnauthorizedObservations() throws Exception {
        JsonNode golden = loadGolden();
        for (JsonNode obs : golden.withArray("observations")) {
            if (!obs.path("case_id").asText().endsWith("::unauthorized")) {
                continue;
            }
            String path = obs.path("request").path("path_template").asText();
            h.newMvc().perform(post(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"missing or malformed bearer token\"}"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden：change-password / logout / password-reset 流（状态码/体）      */
    /* ------------------------------------------------------------------ */

    @Test
    void goldenChangePasswordSuccess() throws Exception {
        // 用独立主体避免与 golden 登录用例的顺序依赖（golden 体只断言 204/空体）。
        String token = login("U20270000041", "U20270000041");
        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + token)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "old_password", "U20270000041", "new_password", "newpassword123"))))
                .andExpect(status().isNoContent())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void goldenLogoutSuccess() throws Exception {
        String token = login("U20270000051", "U20270000051");
        h.newMvc().perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void goldenPasswordResetRequestAndConfirm() throws Exception {
        h.newMvc().perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json")
                        .content("{\"username\":\"U20270000021\"}"))
                .andExpect(status().isAccepted())
                .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray()).isEmpty());
        String requestId = h.users.listResetRequests("pending", null).get(0).id();
        var issue = h.users.approveResetRequest(requestId, "U20270000002", null).orElseThrow();
        h.newMvc().perform(post("/api/auth/password-reset")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "token", issue.token(), "new_password", "resetpassword123"))))
                .andExpect(status().isNoContent());
    }

    /* ------------------------------------------------------------------ */
    /* 验收 A：不存在/错密/停用同一 401 字节；锁定对不存在账号同样生效           */
    /* ------------------------------------------------------------------ */

    @Test
    void acceptanceA_same401ForUnknownWrongPasswordAndDisabled() throws Exception {
        h.seedUser("U20260000101", "[\"viewer\"]", 50, true, true, "U20260000101");
        h.setEnabled("U20260000101", false);
        MockMvc mvc = h.newMvc();
        String wrong = loginBody(mvc, "U20260000101", "wrong-password");
        String unknown = loginBody(mvc, "U20260000999", "wrong-password");
        String disabled = loginBody(mvc, "U20260000101", "U20260000101");
        assertThat(wrong).isEqualTo(unknown).isEqualTo(disabled)
                .isEqualTo("{\"detail\":\"invalid username or password\"}");
        // 401 登录失败无 WWW-Authenticate（Python 该分支无 headers）。
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20260000999\",\"password\":\"x\"}"))
                .andExpect(r -> assertThat(r.getResponse().getHeader("WWW-Authenticate")).isNull());
    }

    @Test
    void acceptanceA_lockoutAppliesToUnknownAccountsIdentically() throws Exception {
        MockMvc mvc = h.newMvc();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .content("{\"username\":\"U20269990101\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20269990101\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /* ------------------------------------------------------------------ */
    /* 验收 E：静态兜底优先、Bearer 大小写、未知/缺失 token、must_change 信息位 */
    /* ------------------------------------------------------------------ */

    @Test
    void acceptanceE_staticFallbackPriorityInAccountMode() throws Exception {
        // 静态兜底 token 命中静态表（优先于 api_tokens）。
        Principal p = h.resolver.resolve(headers("bearer " + AuthTestHarness.FALLBACK_TOKEN),
                new AuthSettings());
        assertThat(p.subject()).isEqualTo("emergency-admin");
        assertThat(p.roles()).containsExactly("admin");
        assertThat(p.dailyQuota()).isZero();
        // 与静态 token 同哈希前缀的表 token 不会被兜底覆盖：常规表 token 仍解析到账号主体。
        String token = login("U20270000011", "U20270000011");
        Principal tablePrincipal = h.resolver.resolve(headers("Bearer " + token), new AuthSettings());
        assertThat(tablePrincipal.subject()).isEqualTo("U20270000011");
        assertThat(tablePrincipal.mustChangePassword()).isTrue(); // 信息标志透出不拦截（验收 E）
    }

    @Test
    void acceptanceE_bearerCaseAndMalformedHeaders() {
        String token = "static-token-value-abcdefghijklmnop";
        AuthServerSettings tokenMode = AuthServerSettings.of("token",
                "{\"" + token + "\":{\"subject\":\"alice\",\"roles\":[\"viewer\"],\"daily_quota\":7}}",
                7, 8, 4, 200);
        AuthSettingsProvider provider = AuthSettingsProvider.fixed(tokenMode);
        AuthPrincipalResolver resolver = new AuthPrincipalResolver(provider, h.users);
        // scheme 大小写不敏感；多余空格 strip。
        assertThat(resolver.resolve(headers("bEaReR   " + token + "  "), new AuthSettings()).subject())
                .isEqualTo("alice");
        // 非 Bearer scheme / 缺失头 → missing；错误 scheme 视为缺失。
        for (String bad : new String[]{null, "", "Basic abc", "Bearer", "Bearer   "}) {
            try {
                resolver.resolve(headers(bad), new AuthSettings());
                throw new AssertionError("expected 401 for header: " + bad);
            } catch (ApiDetailException e) {
                assertThat(e.detail()).isEqualTo("missing or malformed bearer token");
                assertThat(e.headers()).containsEntry("WWW-Authenticate", "Bearer");
            }
        }
        // 未知 token → invalid token（区分文案，同为 401+WWW-Authenticate）。
        try {
            resolver.resolve(headers("Bearer " + "x".repeat(43)), new AuthSettings());
            throw new AssertionError("expected 401");
        } catch (ApiDetailException e) {
            assertThat(e.detail()).isEqualTo("invalid token");
        }
    }

    @Test
    void acceptanceE_tokenModeDoesNotConsultTableTokens() throws Exception {
        // token 模式只认静态表；api_tokens 表 token 不生效（三模式矩阵）。
        String tableToken = login("U20270000021", "U20270000021");
        AuthServerSettings tokenMode = AuthServerSettings.of("token",
                "{\"" + "v".repeat(32) + "\":{\"subject\":\"bob\",\"roles\":[\"viewer\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        AuthPrincipalResolver tokenResolver =
                new AuthPrincipalResolver(AuthSettingsProvider.fixed(tokenMode), h.users);
        try {
            tokenResolver.resolve(headers("Bearer " + tableToken), new AuthSettings());
            throw new AssertionError("token 模式不应解析表 token");
        } catch (ApiDetailException e) {
            assertThat(e.detail()).isEqualTo("invalid token");
        }
    }

    @Test
    void accountModeHiddenBehind404InOtherModes() throws Exception {
        // token/local 模式：体合法 → 404 not found；体非法 → 422（顺序契约）。
        AuthServerSettings tokenMode = AuthServerSettings.of("token",
                "{\"" + "v".repeat(32) + "\":{\"subject\":\"bob\",\"roles\":[\"viewer\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        AuthSettingsProvider provider = AuthSettingsProvider.fixed(tokenMode);
        AuthPrincipalResolver tokenResolver = new AuthPrincipalResolver(provider, h.users);
        MockMvc tokenMvc = h.newMvcWith(provider, tokenResolver);
        tokenMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20270000001\",\"password\":\"U20270000001\"}"))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"not found\"}"));
        tokenMvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());
        // logout：无 token → 401 先于 404；静态 token 通过认证 → 404。
        tokenMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
        tokenMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + "v".repeat(32)))
                .andExpect(status().isNotFound());
        // local 模式：免凭证主体通过认证后仍 404（隐藏端点组）。
        AuthSettingsProvider localProvider =
                AuthSettingsProvider.fixed(AuthServerSettings.defaults());
        MockMvc localMvc = h.newMvcWith(localProvider,
                new AuthPrincipalResolver(localProvider, h.users));
        localMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNotFound());
        localMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20270000001\",\"password\":\"U20270000001\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pydanticBodyFidelityBeyondGolden() throws Exception {
        MockMvc mvc = h.newMvc();
        // 空体 → missing body（input null, loc ["body"]，探针固化）。
        mvc.perform(post("/api/auth/login").contentType("application/json").content(""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(
                        "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\"],\"msg\":\"Field required\",\"input\":null}]}"));
        // 语法错误 → json_invalid（pos=1，ctx.error 探针值）。
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(jsonOf(r).toString()).isEqualTo(
                        "{\"detail\":[{\"type\":\"json_invalid\",\"loc\":[\"body\",1],"
                                + "\"msg\":\"JSON decode error\",\"input\":{},"
                                + "\"ctx\":{\"error\":\"Expecting property name enclosed in double quotes\"}}]}"));
        // 尾随垃圾 → Extra data（pos=第一个非空白尾字符）。
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"a\",\"password\":\"b\"} extra"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(jsonOf(r).path("detail").get(0).path("loc").get(1).asInt())
                        .isEqualTo(32));
        // 非对象 → model_attributes_type；null 字段 → string_type + missing 混合；空串 → too_short。
        mvc.perform(post("/api/auth/login").contentType("application/json").content("[1,2]"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(jsonOf(r).path("detail").get(0).path("type").asText())
                        .isEqualTo("model_attributes_type"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> {
                    JsonNode detail = jsonOf(r).path("detail");
                    assertThat(detail.get(0).path("type").asText()).isEqualTo("string_type");
                    assertThat(detail.get(0).path("input").isNull()).isTrue();
                    assertThat(detail.get(1).path("type").asText()).isEqualTo("missing");
                    assertThat(detail.get(1).path("input").toString()).isEqualTo("{\"username\":null}");
                });
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(jsonOf(r).path("detail").get(0).path("msg").asText())
                        .isEqualTo("String should have at least 1 character"));
        // 多余字段忽略（合法体走完整登录流）。
        h.seedUser("U20260000201", "[\"viewer\"]", 50, true, true, "U20260000201");
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20260000201\",\"password\":\"U20260000201\",\"extra\":1}"))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* 辅助                                                                 */
    /* ------------------------------------------------------------------ */

    private String login(String username, String password) throws Exception {
        MvcResult result = h.newMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private String loginBody(MockMvc mvc, String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static JsonNode jsonOf(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static RequestHeaders headers(String authorization) {
        Map<String, String> map = new LinkedHashMap<>();
        if (authorization != null) {
            map.put("Authorization", authorization);
        }
        return new RequestHeaders(map);
    }

    private static JsonNode loadGolden() throws Exception {
        try (var in = AuthContractTest.class.getResourceAsStream("/migration/J2-01/golden-auth.json")) {
            return MAPPER.readTree(in);
        }
    }
}
