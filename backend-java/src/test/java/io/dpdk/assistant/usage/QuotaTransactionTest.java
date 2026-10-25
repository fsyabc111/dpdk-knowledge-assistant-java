package io.dpdk.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.session.J205TestHarness;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-05 配额与账本数据面（验收 G：原子增量、失败回滚、ledger unknown 可验证）。
 *
 * - 契约 usage.QuotaParticipant：quota=0 不记日计数；事务内 countAndCheck 与
 *   caller 事务同提交，超额抛出整笔回滚（同事务先行的其他写入一并撤销）；
 * - 独立版本 checkAndCountDaily 保留源现状（先递增后检查，失败计数留存）；
 * - 并发增量经 PG upsert 原子串行化；
 * - UsageCore 写入侧：定价/未定价/unknown/零调用行、重复 id 主键冲突不静默。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class QuotaTransactionTest {

    private static final String DAY = "2026-10-24";
    private static final LocalDate DAY_DATE = LocalDate.parse(DAY);

    private static J205TestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("quota");
        h.seedUser("U20260000001", "[\"leader\"]", null, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private int chatCount(String subject) throws Exception {
        return Integer.parseInt(h.columnValue("usage_counters", "chat_count",
                "subject=? AND kind='chat'", subject));
    }

    @Test
    void quotaZeroRecordsNothing() throws Exception {
        int before = h.countRows("usage_counters", null);
        // quota=0（不限额）不记日计数。
        h.counters.countAndCheck(null, "U20260000001", DAY_DATE, "chat", 0);
        h.counters.checkAndCountDaily("U20260000001", "chat", 0);
        assertThat(h.countRows("usage_counters", null)).isEqualTo(before);
    }

    @Test
    void inTxExcessThrowsAndRollsBackWholeTransaction() throws Exception {
        String subject = "U20260000002";
        h.runner.required(tx -> {
            h.counters.countAndCheck(tx, subject, DAY_DATE, "chat", 2);
            return null;
        });
        h.runner.required(tx -> {
            h.counters.countAndCheck(tx, subject, DAY_DATE, "chat", 2);
            return null;
        });
        // 第三次超额：同事务先插入的标记行必须一并回滚。
        assertThatThrownBy(() -> h.runner.required(tx -> {
            try {
                tx.connection().createStatement().execute(
                        "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
                                + "VALUES ('sess_marker01','U20260000002','m',now(),now())");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            h.counters.countAndCheck(tx, subject, DAY_DATE, "chat", 2);
            return null;
        })).isInstanceOf(QuotaExceededException.class)
                .hasMessage("daily chat quota exceeded (2/day)")
                .satisfies(e -> assertThat(((QuotaExceededException) e).retryAfterSeconds())
                        .isPositive());
        assertThat(chatCount(subject)).isEqualTo(2);
        assertThat(h.countRows("assistant_sessions", "id=?", "sess_marker01")).isZero();

        // kind 维度独立：grading 计数不影响 chat 计数。
        h.runner.required(tx -> {
            h.counters.countKindInTx(tx, subject, DAY, "grading", 5);
            h.counters.countKindInTx(tx, subject, DAY, "grading", 5);
            return null;
        });
        assertThat(h.countRows("usage_counters", "subject=? AND kind='grading'", subject))
                .isEqualTo(1);
        assertThat(h.columnValue("usage_counters", "chat_count",
                "subject=? AND kind='grading'", subject)).isEqualTo("2");
    }

    @Test
    void independentCheckAndCountDailyKeepsFailedCount() throws Exception {
        String subject = "U20260000003";
        h.counters.checkAndCountDaily(subject, "chat", 1);
        // 第二次抛出，但源现状：递增已提交（失败计数保留，登记差异见 handoff）。
        assertThatThrownBy(() -> h.counters.checkAndCountDaily(subject, "chat", 1))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(h.columnValue("usage_counters", "chat_count", "subject=? AND kind='chat'",
                subject)).isEqualTo("2");
    }

    @Test
    void concurrentIncrementsAreAtomicAndSerializable() throws Exception {
        String subject = "U20260000004";
        int threads = 8;
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    h.runner.required(tx -> {
                        h.counters.countAndCheck(tx, subject, DAY_DATE, "chat", 8);
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            });
            t.start();
            pool.add(t);
        }
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        for (Thread t : pool) {
            t.join(5000);
        }
        // 每线程恰好一次原子增量：无丢失更新、无重复计数。
        assertThat(h.columnValue("usage_counters", "chat_count", "subject=? AND day=?", subject,
                DAY)).isEqualTo(String.valueOf(threads));
        // 第 9 次越限并回滚（计数保持 8）。
        assertThatThrownBy(() -> h.runner.required(tx -> {
            h.counters.countAndCheck(tx, subject, DAY_DATE, "chat", 8);
            return null;
        })).isInstanceOf(QuotaExceededException.class);
        assertThat(h.columnValue("usage_counters", "chat_count", "subject=? AND day=?", subject,
                DAY)).isEqualTo("8");
    }

    /* ------------------------------------------------------------------ */
    /* 账本写入面（UsageCore → LedgerStore）                               */
    /* ------------------------------------------------------------------ */

    @Test
    void entriesToRowsPricesAtWriteTimeAndKeepsUnknownNull() throws Exception {
        Map<String, UsageCore.ModelPrice> prices = UsageCore.parsePrices(
                "{\"qwen3.8-flash\":{\"input_per_1k\":\"0.0008\",\"output_per_1k\":\"0.002\","
                        + "\"currency\":\"CNY\"}}");
        UsageCore.UsageRecorder recorder = new UsageCore.UsageRecorder("U20260000005", null);
        recorder.recordLlm("planner", "qwen3.8-flash",
                Map.of("prompt_tokens", 1000, "completion_tokens", 500, "total_tokens", 1500));
        recorder.recordLlm("finalize", "unpriced-model",
                Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
        recorder.recordLlm("judge", "qwen3.8-flash", null); // unknown：上游没给 usage
        recorder.recordSkipped("rewrite", "cache_hit", "qwen3.8-flash", UsageCore.KIND_LLM);
        recorder.recordOperation(UsageCore.KIND_MCP, "submit_source", 120, 1);

        List<UsageLedgerRow> rows = UsageCore.entriesToRows(recorder.entries(), prices, "v1",
                "req-q", "U20260000005", "sess_q", "2026-10-24T04:00:00.000001+00:00");
        assertThat(rows).hasSize(5);
        // 定价行：成本写入时算好并盖版本（1000*0.0008 + 500*0.002 = 1.8 → 1800000 微单位）。
        UsageLedgerRow priced = rows.get(0);
        // 单价按每 1000 token 计：1000*0.0008/1000 + 500*0.002/1000 = 0.0018 → 1800 微单位。
        assertThat(priced.costMicros()).isEqualTo(1800);
        assertThat(priced.currency()).isEqualTo("CNY");
        assertThat(priced.priceVersion()).isEqualTo("v1");
        // 未定价模型：只报用量，不出价（不编造费率）。
        assertThat(rows.get(1).costMicros()).isNull();
        assertThat(rows.get(1).currency()).isNull();
        assertThat(rows.get(1).priceVersion()).isNull();
        // unknown：token 列保持 null（绝不用 0 顶替）。
        assertThat(rows.get(2).totalTokens()).isNull();
        assertThat(rows.get(2).usageSource()).isEqualTo("unknown");
        // 零调用留痕：call_count=0、无 token、不计费。
        assertThat(rows.get(3).callCount()).isZero();
        assertThat(rows.get(3).usageSource()).isEqualTo("cache_hit");
        // 非 token 操作单列：source=operation。
        assertThat(rows.get(4).usageSource()).isEqualTo("operation");

        assertThat(h.ledger.recordRows(rows)).isEqualTo(5);
        assertThat(h.countRows("upstream_usage_records", "request_id=?", "req-q")).isEqualTo(5);

        // 重复 id：主键冲突如实抛出（不静默丢账）。
        assertThatThrownBy(() -> h.ledger.recordRows(List.of(rows.get(0))))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
    }

    @Test
    void parsePricesSkipsInvalidEntries() {
        Map<String, UsageCore.ModelPrice> prices = UsageCore.parsePrices(
                "{\"ok\":{\"input_per_1k\":\"1\",\"currency\":\"CNY\"},"
                        + "\"no-input\":{\"output_per_1k\":\"1\",\"currency\":\"CNY\"},"
                        + "\"bad-currency\":{\"input_per_1k\":\"1\"},"
                        + "\"negative\":{\"input_per_1k\":\"-1\",\"currency\":\"CNY\"}}");
        assertThat(prices).containsOnlyKeys("ok");
        assertThat(prices.get("ok").outputPer1k()).isEqualByComparingTo("0");
        assertThat(UsageCore.parsePrices("")).isEmpty();
        assertThat(UsageCore.parsePrices("not-json")).isEmpty();
        // token 解析：负数/bool/小数/null 全部归 unknown。
        UsageCore.TokenTriple triple = UsageCore.tokensFromUsage(Map.of(
                "prompt_tokens", -1, "completion_tokens", true, "total_tokens", 1.5));
        assertThat(triple.promptTokens()).isNull();
        assertThat(triple.completionTokens()).isNull();
        assertThat(triple.totalTokens()).isNull();
        // 只给分项时 total=prompt+completion（等价推导非估算）。
        assertThat(UsageCore.tokensFromUsage(Map.of("input_tokens", 3, "output_tokens", 4))
                .totalTokens()).isEqualTo(7);
    }
}
