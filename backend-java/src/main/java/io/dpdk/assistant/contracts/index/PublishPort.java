package io.dpdk.assistant.contracts.index;

/**
 * 索引发布端口（owner J4-01；契约 transaction-ports.json：index.PublishPort）。
 *
 * embedding 事务外；CAS active 指针+物理 changed nodes+release 记录+成员一笔事务；
 * CAS 失败不得覆盖。rollback 需 config/declared/members/physical 核对。
 */
public interface PublishPort {

    PublishReceipt publish(String expectedActive, PreparedIndex index);

    void rollbackChecked(String releaseId);
}
