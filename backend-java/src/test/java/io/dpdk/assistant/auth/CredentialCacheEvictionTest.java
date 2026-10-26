package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 公开纯缓存端口不得暗中读取墙钟或开启数据库事务。 */
class CredentialCacheEvictionTest {
    @Test void explicitCacheEvictionDoesNotReadAnyClockOrOpenDatabaseTransaction() {
        TransactionRunner forbiddenDb = new TransactionRunner() {
            @Override public <T> T required(Function<DbTx, T> work) {
                throw new AssertionError("cache-only eviction must not open database transaction");
            }
        };
        ClockPort forbiddenClock = new ClockPort() {
            public Instant nowUtc() { throw new AssertionError("wall clock read"); }
            public long monotonicNanos() { throw new AssertionError("monotonic clock read"); }
            public String nextSessionIso() { throw new AssertionError("session timestamp read"); }
        };
        var settings = AuthSettingsProvider.fixed(AuthServerSettings.of("account", "{}", 7, 8, 4, 200));
        CredentialInvalidationPort credentials = new UserAuthRepository(forbiddenDb, forbiddenClock, settings);
        credentials.clearTokenCache();
        credentials.clearTokenCache();
    }
}
