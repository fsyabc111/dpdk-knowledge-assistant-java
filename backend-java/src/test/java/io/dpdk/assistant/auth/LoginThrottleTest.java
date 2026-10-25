package io.dpdk.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * J2-01 登录节流与账号锁定（验收 B）：每 IP 登录限额 10/分钟、重置匿名接口独立
 * 5/分钟桶、账号 5 次失败锁 15 分钟；全部用可控单调时钟检查窗口边界与 Retry-After
 * 精确值。MockMvc 默认 remoteAddr=127.0.0.1（单 IP 桶）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class LoginThrottleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AuthTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AuthTestHarness.accountMode("throttle");
        h.seedUser("U20260000001", "[\"viewer\"]", 50, true, true, "U20260000001");
        h.seedUser("U20260000101", "[\"viewer\"]", 50, true, true, "U20260000101");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private MockMvc login() {
        return h.newMvc();
    }

    private MvcResult postLogin(MockMvc mvc, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andReturn();
    }

    /* ------------------------------------------------------------------ */
    /* 每 IP 登录限额（10/分钟滑动窗）                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void perIpLoginRateLimitWindowAndRetryAfter() throws Exception {
        MockMvc mvc = login();
        // t=0：10 次失败（不同学号避免先触发账号锁定）。
        for (int i = 0; i < 10; i++) {
            assertThat(postLogin(mvc, String.format("U20240999%03d", i), "wrong-password").getResponse().getStatus())
                    .isEqualTo(401);
        }
        // 第 11 次（t=0）：429，Retry-After = int(60-0)+1 = 61。
        MvcResult limited = postLogin(mvc, "U20260000001", "wrong-password");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("61");
        assertThat(limited.getResponse().getContentAsString())
                .isEqualTo("{\"detail\":\"login rate limit exceeded (10/minute)\"}");
        // t=30s：窗口内最老条目仍 t=0 → Retry-After = int(60-30)+1 = 31。
        h.clock.advanceMonoSeconds(30);
        limited = postLogin(mvc, "U20260000001", "wrong-password");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("31");
        // t=59.999：429，Retry-After = max(1, int(0.001)+1) = 1（窗口边界）。
        h.clock.advanceMonoSeconds(29.999);
        limited = postLogin(mvc, "U20260000001", "wrong-password");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("1");
        // t=60：最老条目出窗（>=60 剪除）→ 放行。
        h.clock.advanceMonoSeconds(0.001);
        assertThat(postLogin(mvc, "U20260000001", "wrong-password").getResponse().getStatus()).isEqualTo(401);
        // 限流在密码验证之前：即便密码正确也先 429（顺序契约）。
        h.clock.advanceMonoSeconds(0);
        for (int i = 10; i < 20; i++) {
            postLogin(mvc, String.format("U20240999%03d", i), "wrong-password");
        }
        MvcResult correctButLimited = postLogin(mvc, "U20260000001", "U20260000001");
        assertThat(correctButLimited.getResponse().getStatus()).isEqualTo(429);
    }

    /* ------------------------------------------------------------------ */
    /* 账号锁定：5 次失败锁 15 分钟，存在/不存在学号一视同仁                  */
    /* ------------------------------------------------------------------ */

    @Test
    void accountLockoutAfterFiveFailures() throws Exception {
        MockMvc mvc = login();
        for (int i = 0; i < 5; i++) {
            assertThat(postLogin(mvc, "U20260000101", "wrong-password").getResponse().getStatus()).isEqualTo(401);
        }
        // 第 6 次：429（正确密码同样被拒，应答与错误密码完全一致）。
        MvcResult lockedWrong = postLogin(mvc, "U20260000101", "wrong-password");
        MvcResult lockedCorrect = postLogin(mvc, "U20260000101", "U20260000101");
        assertThat(lockedWrong.getResponse().getStatus()).isEqualTo(429);
        assertThat(lockedCorrect.getResponse().getStatus()).isEqualTo(429);
        assertThat(lockedCorrect.getResponse().getContentAsString())
                .isEqualTo(lockedWrong.getResponse().getContentAsString())
                .isEqualTo("{\"detail\":\"too many failed login attempts, try again later\"}");
        assertThat(lockedWrong.getResponse().getHeader("Retry-After")).isEqualTo("901"); // int(900)+1
        // 锁定自然结束（t+900s）：正确密码恢复登录且成功清零计数。
        h.clock.advanceMonoSeconds(900);
        assertThat(postLogin(mvc, "U20260000101", "U20260000101").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void lockoutAppliesToUnknownUsersAndMidTermRetryAfter() throws Exception {
        MockMvc mvc = login();
        for (int i = 0; i < 5; i++) {
            assertThat(postLogin(mvc, "U20269990101", "wrong-password").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult locked = postLogin(mvc, "U20269990101", "wrong-password");
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isEqualTo("901");
        h.clock.advanceMonoSeconds(100); // 锁定进行中：int(800)+1。
        locked = postLogin(mvc, "U20269990101", "wrong-password");
        assertThat(locked.getResponse().getHeader("Retry-After")).isEqualTo("801");
        h.clock.advanceMonoSeconds(800);
        // 锁定期外（check 顺带清除锁记录）→ 未达到失败阈值窗口，可正常 401/200。
        assertThat(postLogin(mvc, "U20269990101", "wrong-password").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void lockoutWindowExpiresPartialFailures() throws Exception {
        MockMvc mvc = login();
        // 4 次失败后滑窗过期（>=900s 剪除），再 1 次失败不会锁号。
        for (int i = 0; i < 4; i++) {
            postLogin(mvc, "U20260000201", "wrong-password");
        }
        h.clock.advanceMonoSeconds(900);
        postLogin(mvc, "U20260000201", "wrong-password");
        h.clock.advanceMonoSeconds(1);
        assertThat(postLogin(mvc, "U20260000201", "wrong-password").getResponse().getStatus()).isEqualTo(401);
    }

    /* ------------------------------------------------------------------ */
    /* 重置匿名接口独立限流（5/分钟，kind=password_reset）                    */
    /* ------------------------------------------------------------------ */

    @Test
    void resetEndpointsHaveIndependentBuckets() throws Exception {
        MockMvc mvc = login();
        for (int i = 0; i < 5; i++) {
            assertThat(mvc.perform(post("/api/auth/password-reset-requests")
                            .contentType("application/json")
                            .content("{\"username\":\"U20260000001\"}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(202);
        }
        MvcResult limited = mvc.perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json")
                        .content("{\"username\":\"U20260000001\"}"))
                .andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString())
                .isEqualTo("{\"detail\":\"password_reset rate limit exceeded (5/minute)\"}");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("61");
        // reset-confirm 与 reset-request 分桶：request 被限流后 confirm 仍可用（400 令牌无效）。
        MvcResult confirm = mvc.perform(post("/api/auth/password-reset")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "token", "no-such-token", "new_password", "resetpassword123"))))
                .andReturn();
        assertThat(confirm.getResponse().getStatus()).isEqualTo(400);
        assertThat(confirm.getResponse().getContentAsString())
                .isEqualTo("{\"detail\":\"invalid or expired reset token\"}");
        // confirm 桶独立计满 5 次后同样 429。
        for (int i = 0; i < 4; i++) {
            assertThat(mvc.perform(post("/api/auth/password-reset")
                            .contentType("application/json")
                            .content(MAPPER.writeValueAsString(java.util.Map.of(
                                    "token", "no-such-token", "new_password", "resetpassword123"))))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        MvcResult confirmLimited = mvc.perform(post("/api/auth/password-reset")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "token", "no-such-token", "new_password", "resetpassword123"))))
                .andReturn();
        assertThat(confirmLimited.getResponse().getStatus()).isEqualTo(429);
        // 登录桶独立于重置桶：上面 11 次重置请求不影响登录。
        assertThat(postLogin(mvc, "U20260000001", "U20260000001").getResponse().getStatus()).isEqualTo(200);
    }

    /* ------------------------------------------------------------------ */
    /* 429/401 头与体契约                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void throttle429CarriesRetryAfterAnd401HasNoWwwAuthenticate() throws Exception {
        MockMvc mvc = login();
        for (int i = 0; i < 10; i++) {
            postLogin(mvc, String.format("U20241999%03d", i), "wrong-password");
        }
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"U20260000001\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "61"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Content-Type", "application/json"));
    }
}
