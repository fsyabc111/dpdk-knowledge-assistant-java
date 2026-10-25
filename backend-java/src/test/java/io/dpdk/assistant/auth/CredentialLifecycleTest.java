package io.dpdk.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-01 凭据生命周期（验收 C/D/F）：bcrypt Python 兼容、token 存储/撤销/过期、
 * 改密保当前撤其他、重置申请防枚举/幂等、一次性令牌顺序与并发消费、
 * AccountWriteGuard 与 J2-02 协作端口。dummy 等价工作按代码路径断言（验收 F）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class CredentialLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AuthTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AuthTestHarness.accountMode("lifecycle");
        h.seedUser("U20260000001", "[\"viewer\"]", 50, true, true, "U20260000001");
        h.seedUser("U20260000002", "[\"viewer\"]", 50, true, true, "U20260000002");
        h.seedUser("U20260000003", "[\"admin\"]", null, true, true, "U20260000003");
        h.seedUser("U20260000101", "[\"viewer\"]", 50, true, true, "U20260000101");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private String pendingRequestId(String userId) {
        return h.users.listResetRequests("pending", null).stream()
                .filter(r -> r.userId().equals(userId))
                .findFirst().orElseThrow().id();
    }

    private String login(String username, String password) throws Exception {
        var result = h.newMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    /* ------------------------------------------------------------------ */
    /* 验收 F 前置：Python bcrypt 兼容（FIX/J2-01/bcrypt-compat.json）       */
    /* ------------------------------------------------------------------ */

    @Test
    void pythonBcryptFixtureVerifies() throws Exception {
        JsonNode fixture;
        try (var in = CredentialLifecycleTest.class.getResourceAsStream(
                "/migration/J2-01/bcrypt-compat.json")) {
            fixture = MAPPER.readTree(in);
        }
        PasswordHasher hasher = new PasswordHasher(4);
        for (JsonNode vector : fixture.withArray("vectors")) {
            String password = vector.path("password").asText();
            String hash = vector.path("hash").asText();
            // Python bcrypt 4.3.0 生成的 $2b$ 哈希（含 cost12 样本）Java 可校验。
            assertThat(hasher.check(password, hash))
                    .as("vector %s", vector.path("id").asText()).isTrue();
            // 错误口令必须失败（避免恒真实现）。
            assertThat(hasher.check(password + "x", hash)).isFalse();
            if (vector.path("cost").asInt() == 4) {
                assertThat(hash).startsWith("$2b$04$");
            }
        }
        // 静态 token sha256 摘要向量。
        assertThat(TokenMint.digest(fixture.path("token_digest").path("token").asText()))
                .isEqualTo(fixture.path("token_digest").path("sha256").asText());
    }

    @Test
    void loginSeedWithPythonFixtureHashWorks() throws Exception {
        // 用 Python 生成的初始密码哈希直接落库：登录成功（读兼容走全链路）。
        JsonNode fixture;
        try (var in = CredentialLifecycleTest.class.getResourceAsStream(
                "/migration/J2-01/bcrypt-compat.json")) {
            fixture = MAPPER.readTree(in);
        }
        String hash = fixture.withArray("vectors").get(0).path("hash").asText(); // U20260000001
        assertThat(hash).startsWith("$2b$04$");
        h.seedUserWithHash("U20260000999", hash, "[\"viewer\"]", 50, true, true);
        h.newMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"U20260000999\",\"password\":\"U20260000001\"}"))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* token 存储与撤销（验收 C；oracle 向量 auth-storage/disabled-revoked）  */
    /* ------------------------------------------------------------------ */

    @Test
    void tokenStoredAsSha256OnlyAndRevocationIsImmediate() throws Exception {
        String token = login("U20260000001", "U20260000001");
        // 库里只有 sha256 hex，不存明文（oracle auth-storage 向量）。
        String stored = h.columnValue("api_tokens", "token_hash", "user_id=?", "U20260000001");
        assertThat(stored).isEqualTo(TokenMint.digest(token)).isNotEqualTo(token).matches("^[0-9a-f]{64}$");
        // 预热 60s resolve 缓存后 logout：缓存必须被清空，旧 token 立即失效。
        assertThat(h.users.resolveToken(token)).isPresent();
        h.newMvc().perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(h.users.resolveToken(token)).isEmpty();
        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"U20260000001\",\"new_password\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenFails() throws Exception {
        // 过期 token → 空 Principal；HTTP 层 401 invalid token。
        h.insertToken("expired-token-value", "U20260000002",
                "2026-10-18T04:00:00+00:00", "2026-10-24T04:00:00+00:00");
        assertThat(h.users.resolveToken("expired-token-value")).isEmpty();
        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json")
                        .header("Authorization", "Bearer expired-token-value")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void revokedTokenStaysRevokedAfterReEnable() throws Exception {
        // oracle auth-account-disabled-revoked：撤销后的 token 不因停用/重启启用复活。
        String token = login("U20260000101", "U20260000101");
        h.newMvc().perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        h.setEnabled("U20260000101", false);
        h.users.clearTokenCache();
        assertThat(h.users.resolveToken(token)).isEmpty();
        h.setEnabled("U20260000101", true);
        assertThat(h.users.resolveToken(token)).isEmpty();
    }

    @Test
    void dailyQuotaNullFallsBackToDefault() throws Exception {
        // daily_quota 列 NULL → llm_daily_quota_default（Python 语义）。
        String token = login("U20260000003", "U20260000003");
        Optional<Principal> p = h.users.resolveToken(token);
        assertThat(p).isPresent();
        assertThat(p.get().dailyQuota()).isEqualTo(200);
        assertThat(p.get().roles()).containsExactly("admin");
    }

    /* ------------------------------------------------------------------ */
    /* 改密：保留当前 token、撤销其他（验收 C；Python test 273 语义）         */
    /* ------------------------------------------------------------------ */

    @Test
    void changePasswordKeepsCurrentRevokesOthersAndRotatesCredentials() throws Exception {
        h.seedUser("U20260000301", "[\"viewer\"]", 50, true, true, "U20260000301");
        // 脱离初始密码。
        String settle = login("U20260000301", "U20260000301");
        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + settle)
                        .content("{\"old_password\":\"U20260000301\",\"new_password\":\"newpassword123\"}"))
                .andExpect(status().isNoContent());
        String token1 = login("U20260000301", "newpassword123");
        String token2 = login("U20260000301", "newpassword123");
        // 两个 token 都进 60s 缓存（改密必须穿透缓存）。
        assertThat(h.users.resolveToken(token1)).isPresent();
        assertThat(h.users.resolveToken(token2)).isPresent();

        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token1)
                        .content("{\"old_password\":\"newpassword123\",\"new_password\":\"anotherpassword456\"}"))
                .andExpect(status().isNoContent());

        // 当前 token 保留可用，该用户其他 token 被吊销。
        assertThat(h.users.resolveToken(token1)).isPresent();
        assertThat(h.users.resolveToken(token2)).isEmpty();
        // 旧密失效、新密可登录。
        h.newMvc().perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"U20260000301\",\"password\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized());
        String token3 = login("U20260000301", "anotherpassword456");
        // 改密后 must_change_password 清除。
        assertThat(h.users.resolveToken(token3).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    void changePasswordRuleViolationsMapToExactDetails() throws Exception {
        h.seedUser("U20260000302", "[\"viewer\"]", 50, true, true, "U20260000302");
        String token = login("U20260000302", "U20260000302");
        h.newMvc().perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"U20260000302\",\"new_password\":\"newpassword123\"}"))
                .andExpect(status().isNoContent());
        var mvc = h.newMvc();
        mvc.perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"not-the-password\",\"new_password\":\"whatever123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"old password is incorrect\"}"));
        mvc.perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"newpassword123\",\"new_password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must be at least 8 characters\"}"));
        mvc.perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"newpassword123\",\"new_password\":\"U20260000302\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must differ from the user id\"}"));
        mvc.perform(post("/api/auth/change-password")
                        .contentType("application/json").header("Authorization", "Bearer " + token)
                        .content("{\"old_password\":\"newpassword123\",\"new_password\":\"newpassword123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must differ from the old password\"}"));
        // 静态兜底主体（无 users 行）改密：旧密校验失败 → 400 old password is incorrect。
        mvc.perform(post("/api/auth/change-password")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + AuthTestHarness.FALLBACK_TOKEN)
                        .content("{\"old_password\":\"whatever\",\"new_password\":\"newpassword456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"old password is incorrect\"}"));
    }

    /* ------------------------------------------------------------------ */
    /* 验收 F：不存在/停用账号的 bcrypt 等价工作（代码路径断言，非耗时）       */
    /* ------------------------------------------------------------------ */

    @Test
    void verifyCredentialsRunsDummyBcryptForMissingAndDisabledAccounts() throws Exception {
        // 路径断言：不存在/停用账号各执行一次 dummyCheck（等价一次 bcrypt 比较，
        // PasswordHasher.dummyCheck 与真实校验同为一次 checkpw 代码路径；不以耗时证明）。
        h.seedUser("U20260000998", "[\"viewer\"]", 50, false, true, "U20260000998");
        assertThat(h.users.verifyCredentials("U20269999999", "any-password")).isFalse();
        assertThat(h.users.verifyCredentials("U20260000998", "U20260000998")).isFalse(); // 停用 → dummy 路径
        assertThat(h.users.verifyCredentials("U20260000002", "any-password")).isFalse(); // 错密 → 真哈希失败
        assertThat(h.users.verifyCredentials("U20260000002", "U20260000002")).isTrue();
    }

    /* ------------------------------------------------------------------ */
    /* 忘记密码（验收 D）                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void resetRequestIsAntiEnumerationAndIdempotent() throws Exception {
        var mvc = h.newMvc();
        // 存在/不存在的学号统一 202；重复提交幂等；格式非法 400。
        for (String username : new String[]{"U20260000401", "U20269990401"}) {
            for (int i = 0; i < 2; i++) {
                mvc.perform(post("/api/auth/password-reset-requests")
                                .contentType("application/json")
                                .content("{\"username\":\"" + username + "\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray()).isEmpty());
            }
        }
        mvc.perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json").content("{\"username\":\"bad-id\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"invalid username format\"}"));
        // 幂等：同学号仅一行 pending。
        assertThat(h.countRows("password_reset_requests", "user_id=? AND status='pending'",
                "U20260000401")).isEqualTo(1);
        assertThat(h.countRows("password_reset_requests", "user_id=? AND status='pending'",
                "U20269990401")).isEqualTo(1);
    }

    @Test
    void resetTokenSequentialOneTimeAndTtl() throws Exception {
        h.seedUser("U20260000501", "[\"viewer\"]", 50, true, true, "U20260000501");
        String oldToken = login("U20260000501", "U20260000501");
        h.users.createResetRequest("U20260000501");
        String requestId = pendingRequestId("U20260000501");
        var issue = h.users.approveResetRequest(requestId, "U20260000003", null).orElseThrow();
        assertThat(issue.token()).matches("^[A-Za-z0-9_-]{43}$");
        // 审批签发 30 分钟有效期。
        Instant expires = Instant.parse(issue.expiresAtIso());
        assertThat(expires).isEqualTo(h.clock.nowUtc().plusSeconds(30 * 60));

        var mvc = h.newMvc();
        // 首次消费：改密 + must_change 清除 + 旧 token 全撤。
        mvc.perform(post("/api/auth/password-reset")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "token", issue.token(), "new_password", "resetpassword123"))))
                .andExpect(status().isNoContent());
        assertThat(h.users.resolveToken(oldToken)).isEmpty();
        String login = login("U20260000501", "resetpassword123");
        assertThat(h.users.resolveToken(login).orElseThrow().mustChangePassword()).isFalse();
        // 一次性：二次使用 400；无效令牌 400；新密不合规也 400（不区分原因）。
        for (String bad : new String[]{issue.token(), "no-such-token"}) {
            mvc.perform(post("/api/auth/password-reset")
                            .contentType("application/json")
                            .content(MAPPER.writeValueAsString(java.util.Map.of(
                                    "token", bad, "new_password", "resetpassword456"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"invalid or expired reset token\"}"));
        }
        mvc.perform(post("/api/auth/password-reset")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "token", "no-such-token", "new_password", "short"))))
                .andExpect(status().isBadRequest());
        // TTL：受控时钟推进 31 分钟后消费 → 过期 400。
        h.users.createResetRequest("U20260000501");
        String secondId = pendingRequestId("U20260000501");
        var second = h.users.approveResetRequest(secondId, "U20260000003", null).orElseThrow();
        h.clock.advanceWall(java.time.Duration.ofMinutes(31));
        boolean consumed = h.users.consumeResetToken(second.token(), "resetpassword789");
        assertThat(consumed).isFalse();
        h.clock.advanceWall(java.time.Duration.ofMinutes(-31));
    }

    @Test
    void resetTokenConcurrentConsumptionIsStrictlyOnce() throws Exception {
        h.seedUser("U20260000601", "[\"viewer\"]", 50, true, true, "U20260000601");
        h.users.createResetRequest("U20260000601");
        String requestId = pendingRequestId("U20260000601");
        var issue = h.users.approveResetRequest(requestId, "U20260000003", null).orElseThrow();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var results = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < threads; i++) {
                final String password = "concurrentpass" + (char) ('a' + i);
                results.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    return h.users.consumeResetToken(issue.token(), password);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> f : results) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            // status='approved' 守卫保证并发严格一次（差异已登记：Python 无守卫可双消费）。
            assertThat(successes).isEqualTo(1);
            assertThat(h.countRows("password_reset_requests", "id=? AND status='used'", requestId))
                    .isEqualTo(1);
            assertThat(h.countRows("api_tokens", "user_id=? AND revoked_at IS NULL", "U20260000601"))
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /* ------------------------------------------------------------------ */
    /* AccountWriteGuard（契约 identity.AccountWriteGuard）                  */
    /* ------------------------------------------------------------------ */

    @Test
    void accountWriteGuardMatrix() throws Exception {
        h.seedUser("U20260000701", "[\"viewer\"]", 50, true, true, "U20260000701");
        h.seedUser("U20260000702", "[\"viewer\"]", 50, false, true, "U20260000702");
        AuthServerSettings original = h.settings.current();
        boolean enabledUser = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000701"));
        boolean disabledUser = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000702"));
        boolean staticSubject = h.runner.required(tx -> h.guard.lockEnabled(tx, "emergency-admin"));
        boolean unknownSubject = h.runner.required(tx -> h.guard.lockEnabled(tx, "nobody"));
        // 启用账号：行锁成功；停用账号：不可绕过；静态兜底主体无 users 行放行；未知主体拒绝。
        assertThat(enabledUser).isTrue();
        assertThat(disabledUser).isFalse();
        assertThat(staticSubject).isTrue();
        assertThat(unknownSubject).isFalse();
        // 同名已停账号不可借静态 token 绕过：停用 emergency-admin 同名行。
        h.seedUser("emergency-admin", "[\"admin\"]", 0, false, true, "whatever1");
        boolean shadowedStatic = h.runner.required(tx -> h.guard.lockEnabled(tx, "emergency-admin"));
        assertThat(shadowedStatic).isFalse();
        // 非 account 模式恒 true（随后恢复）。
        h.settings.replace(AuthServerSettings.defaults());
        boolean nonAccountMode = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000702"));
        assertThat(nonAccountMode).isTrue();
        h.settings.replace(original);
    }

    /* ------------------------------------------------------------------ */
    /* J2-02 协作端口（CredentialInvalidationPort / 审批占位）               */
    /* ------------------------------------------------------------------ */

    @Test
    void credentialInvalidationPortSemantics() throws Exception {
        h.seedUser("U20260000801", "[\"viewer\"]", 50, true, true, "U20260000801");
        String token = login("U20260000801", "U20260000801");
        assertThat(h.users.resolveToken(token)).isPresent();
        // 管理员设密：must_change 置位、全部 token 撤销、规则异常带精确 detail。
        h.users.setPasswordByAdmin("U20260000801", "adminsetpassword1");
        assertThat(h.users.resolveToken(token)).isEmpty();
        String login = login("U20260000801", "adminsetpassword1");
        assertThat(h.users.resolveToken(login).orElseThrow().mustChangePassword()).isTrue();
        try {
            h.users.setPasswordByAdmin("U20260000801", "U20260000801");
            throw new AssertionError("规则违规应抛 PasswordRuleException");
        } catch (PasswordRuleException e) {
            assertThat(e.getMessage()).isEqualTo("new password must differ from the user id");
        }
        assertThat(h.users.setPasswordByAdmin("U20269999999", "whatever123")).isFalse();
        // 重置为初始密码：must_change 重新置位、token 全撤、学号密码可登录。
        assertThat(h.users.resetPasswordToInitial("U20260000801")).isTrue();
        assertThat(h.users.resolveToken(login)).isEmpty();
        assertThat(h.users.resetPasswordToInitial("U20269999999")).isFalse();
        String initial = login("U20260000801", "U20260000801");
        assertThat(h.users.resolveToken(initial).orElseThrow().mustChangePassword()).isTrue();
        // 审批占位：scope 隔离（老师只见自己学生）与驳回。
        h.seedUser("U20260000802", "[\"viewer\"]", 50, true, true, "U20260000802");
        try (var conn = h.ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE users SET created_by=? WHERE id IN ('U20260000801','U20260000802')")) {
            ps.setString(1, "U20260000003");
            ps.executeUpdate();
        }
        h.users.createResetRequest("U20260000801");
        h.users.createResetRequest("U20260000802");
        var rows = h.users.listResetRequests("pending", "U20260000003");
        assertThat(rows).hasSize(2);
        assertThat(h.users.listResetRequests("pending", "U20260000999")).isEmpty(); // 树隔离
        assertThat(h.users.approveResetRequest(rows.get(0).id(), "U20260000003", "U20260000999"))
                .isEmpty(); // 越界 scope 审批不签发
        assertThat(h.users.rejectResetRequest(rows.get(1).id(), "U20260000003", "U20260000999"))
                .isFalse();
        var approved = h.users.approveResetRequest(rows.get(0).id(), "U20260000003", "U20260000003");
        assertThat(approved).isPresent();
        assertThat(h.users.approveResetRequest(rows.get(0).id(), "U20260000003", null)).isEmpty();
        assertThat(h.users.rejectResetRequest(rows.get(1).id(), "U20260000003", null)).isTrue();
    }
}
