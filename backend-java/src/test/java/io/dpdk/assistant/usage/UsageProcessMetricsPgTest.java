package io.dpdk.assistant.usage;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import io.dpdk.assistant.task.TaskTestHarness;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 过程计数在实际事务提交后发生；回滚不增加、后续留存删除不倒减counter。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class UsageProcessMetricsPgTest {
    private static UsageLedgerRow row(String suffix,String source,Integer tokens,Integer cost) {
        return new UsageLedgerRow("use_"+suffix,"synthetic-request",null,null,"llm","planner","synthetic-model",
                tokens,0,tokens,source,source.equals("cache_hit")?0:1,1,cost,cost==null?null:"USD","synthetic","2026-10-25T04:00:00+00:00");
    }
    @Test void onlyCommittedRowsReachProcessCounters() throws Exception {
        var h=TaskTestHarness.create("usagemetric");
        try {
            var samples=new ArrayList<ProcessMetrics.UsageSample>();
            var ledger=new UsageLedgerStore(h.runner);
            ledger.setMetrics(new ProcessMetrics() {public void usage(UsageSample sample){samples.add(sample);}});
            assertThrows(IllegalStateException.class,()->h.runner.required(tx->{
                ledger.recordRows(List.of(row("111111111111","upstream",21,7)));
                assertTrue(samples.isEmpty());
                throw new IllegalStateException("synthetic rollback");
            }));
            assertTrue(samples.isEmpty());
            h.runner.required(tx->{ledger.recordRows(List.of(row("222222222222","upstream",21,7),
                    row("333333333333","unknown",null,null),row("444444444444","cache_hit",null,0)));
                assertTrue(samples.isEmpty());return null;});
            assertEquals(3,samples.size());assertEquals(21,samples.get(0).totalTokens().intValue());assertEquals(7L,samples.get(0).costMicros().longValue());
            assertNull(samples.get(1).totalTokens());assertEquals("unknown",samples.get(1).source());
            assertEquals("cache_hit",samples.get(2).source());
            h.runner.required(tx->{try(var statement=tx.connection().createStatement()) { statement.executeUpdate("DELETE FROM upstream_usage_records"); }
                catch(java.sql.SQLException e){throw new IllegalStateException(e);}return null;});
            assertEquals(3,samples.size());
        } finally {h.drop();}
    }
}
