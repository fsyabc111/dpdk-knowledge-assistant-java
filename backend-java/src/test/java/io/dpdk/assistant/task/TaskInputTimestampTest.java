package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 源_store_task_inputs_in_conn只采一次普通UTC，created/expires使用同一基准。 */
class TaskInputTimestampTest {
    private static final Instant T0 = Instant.parse("2026-10-24T04:00:00.123456Z");
    private static final Duration TTL = Duration.ofHours(24);

    private static final class CountingClock implements ClockPort {
        final AtomicInteger wallReads = new AtomicInteger();
        final AtomicInteger sessionReads = new AtomicInteger();
        public Instant nowUtc() { return T0.plusSeconds(wallReads.getAndIncrement()); }
        public String nextSessionIso() {
            sessionReads.incrementAndGet();
            return SystemClockAdapter.pythonIso(T0.plusSeconds(100));
        }
        public long monotonicNanos() { throw new AssertionError("持久时间不读预算时钟"); }
    }

    @Test void inputsReadOneOrdinaryUtcAndBindExact24HoursToBothUpsertArms() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        Map<Integer, String> bound = new HashMap<>();
        doAnswer(invocation -> { bound.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(statement).setString(anyInt(), nullable(String.class));
        var clock = new CountingClock();
        new TaskStore(null, clock, null, TTL).storeInputs(() -> connection,
                "synthetic-task", "合成问题", null, null, "synthetic-only", "upload");
        assertThat(clock.wallReads).hasValue(1);
        assertThat(clock.sessionReads).hasValue(0);
        assertThat(bound.get(7)).isEqualTo(SystemClockAdapter.pythonIso(T0));
        assertThat(bound.get(8)).isEqualTo(SystemClockAdapter.pythonIso(T0.plus(TTL)));
        assertThat(bound.get(14)).isEqualTo(bound.get(7));
        assertThat(bound.get(15)).isEqualTo(bound.get(8));
        verify(statement).executeUpdate();
        verify(connection, never()).commit(); // 保持调用方事务所有权。
    }

    @Test void requestInsertStillUsesExistingMonotoneSessionStamp() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        var clock = new CountingClock();
        assertThat(new TaskStore(null, clock, null, TTL).insertRequest(() -> connection,
                "task", "request", "hash", true, "chat", "student", "session", "question", "", "fixed")).isTrue();
        assertThat(clock.wallReads).hasValue(0);
        assertThat(clock.sessionReads).hasValue(1);
        verify(statement).setString(11, SystemClockAdapter.pythonIso(T0.plusSeconds(100)));
        verify(statement).setString(12, SystemClockAdapter.pythonIso(T0.plusSeconds(100)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
    void actualPgInputInsertAndUpsertKeepOneWallSampleAndSameTtlBaseline() throws Exception {
        String database = TestPg.createDatabase("p5inputclock", TestPg.COMMENT);
        try {
            var ds = TestPg.dataSource(database);
            new MigrationController().run(ds, TestPg.identity(database), true);
            var clock = new CountingClock();
            var store = new TaskStore(null, clock, null, TTL);
            for (int revision = 0; revision < 2; revision++) {
                try (Connection conn = ds.getConnection()) {
                    conn.setAutoCommit(false);
                    store.storeInputs(() -> conn, "synthetic-task", "question-"+revision, null, null, "synthetic-only", "upload");
                    conn.commit();
                }
                try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(
                        "SELECT question,created_at,expires_at FROM rag_task_inputs WHERE task_id=?")) {
                    ps.setString(1, "synthetic-task");
                    try (ResultSet row = ps.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo("question-"+revision);
                        Instant created = OffsetDateTime.parse(row.getString(2)).toInstant();
                        Instant expires = OffsetDateTime.parse(row.getString(3)).toInstant();
                        assertThat(created).isEqualTo(T0.plusSeconds(revision));
                        assertThat(Duration.between(created, expires)).isEqualTo(TTL);
                        assertThat(row.next()).isFalse();
                    }
                }
                assertThat(clock.wallReads).hasValue(revision+1);
                assertThat(clock.sessionReads).hasValue(0);
            }
        } finally { TestPg.dropDatabase(database); }
    }
}
