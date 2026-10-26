package io.dpdk.assistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.time.Instant;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.identity.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 公开管理写入→真实已暖token解析：提交后无需等待TTL即可看到权限/配额。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AdminTokenCacheCoherenceTest {
    private AdminTestHarness h;
    private static final String USER = "U20260000301";
    @BeforeEach void setup() throws Exception { h = AdminTestHarness.accountMode("p5cache"); }
    @AfterEach void close() throws Exception { if (h != null) h.drop(); }

    @Test void warmedTokenImmediatelyReflectsCommittedRolesAndQuota() {
        h.users.createUsers(List.of(USER), List.of("viewer"), 12, null);
        String token = h.auth.issueToken(USER).token();
        assertThat(h.auth.resolveToken(token).orElseThrow().roles()).containsExactly("viewer");
        assertThat(h.auth.resolveToken(token).orElseThrow().dailyQuota()).isEqualTo(12);
        h.users.updateUser(USER, List.of("admin"), 3, null).orElseThrow();
        var updated = h.auth.resolveToken(token).orElseThrow();
        assertThat(updated.roles()).containsExactly("admin");
        assertThat(updated.dailyQuota()).isEqualTo(3);
    }
    @Test void disablingWarmAccountRevokesTokenWithoutSecondTimestampOrWriteTransaction() {
        var clock = new CountingClock(h.clock);
        var auth = new UserAuthRepository(h.runner, clock, h.settings);
        var admin = new AdminUserRepository(h.runner, clock, h.settings, auth, auth);
        admin.createUsers(List.of(USER), List.of("viewer"), 12, null);
        String token = auth.issueToken(USER).token();
        assertThat(auth.resolveToken(token)).isPresent();
        clock.timestamps = 0; clock.wallReads = 0;
        assertThat(admin.setEnabled(USER, false)).isTrue();
        assertThat(clock.timestamps).as("仅事务内撤销写入一次时间戳，提交后纯清缓存").isEqualTo(1);
        assertThat(clock.wallReads).isZero();
        assertThat(auth.resolveToken(token)).isEmpty();
        assertThat(admin.getUser(USER).orElseThrow().enabled()).isFalse();
    }

    @Test void deletingWarmAccountClearsTokenWithoutPostCommitRevocationTimestamp() {
        var clock = new CountingClock(h.clock);
        var auth = new UserAuthRepository(h.runner, clock, h.settings);
        var admin = new AdminUserRepository(h.runner, clock, h.settings, auth, auth);
        String leader = "U20260000300";
        admin.createUsers(List.of(leader), List.of("leader"), null, null);
        admin.createUsers(List.of(USER), List.of("viewer"), 12, null);
        String token = auth.issueToken(USER).token();
        assertThat(auth.resolveToken(token)).isPresent();
        clock.timestamps = 0; clock.wallReads = 0;
        var result = admin.deleteUsers(new Principal(leader, List.of("leader"), 200, true), List.of(USER), leader);
        assertThat(result.affected()).containsExactly(USER);
        assertThat(clock.timestamps).as("只写审计时间戳，已删除token不再开撤销事务").isEqualTo(1);
        assertThat(clock.wallReads).isZero();
        assertThat(auth.resolveToken(token)).isEmpty();
        assertThat(admin.getUser(USER)).isEmpty();
    }

    static final class CountingClock implements ClockPort {
        final ClockPort delegate; int timestamps; int wallReads;
        CountingClock(ClockPort delegate) { this.delegate = delegate; }
        public Instant nowUtc() { wallReads++; return delegate.nowUtc(); }
        public long monotonicNanos() { return delegate.monotonicNanos(); }
        public String nextSessionIso() { timestamps++; return delegate.nextSessionIso(); }
    }

}
