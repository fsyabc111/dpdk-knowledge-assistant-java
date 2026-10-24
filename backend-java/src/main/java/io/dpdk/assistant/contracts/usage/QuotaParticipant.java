package io.dpdk.assistant.contracts.usage;

import io.dpdk.assistant.contracts.platform.DbTx;
import java.time.LocalDate;

/**
 * 配额参与端口（owner J2-05；契约 transaction-ports.json：usage.QuotaParticipant）。
 *
 * quota=0 不记日计数；chat 原子受理与 request 唯一键同提交，超额抛出回滚；
 * 独立事务版先递增后检查的失败计数现状须保留/登记差异。
 */
public interface QuotaParticipant {

    void countAndCheck(DbTx tx, String subject, LocalDate utcDay, String kind, int quota);
}
