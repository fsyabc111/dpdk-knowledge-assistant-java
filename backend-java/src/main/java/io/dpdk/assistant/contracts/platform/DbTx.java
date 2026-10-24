package io.dpdk.assistant.contracts.platform;

import java.sql.Connection;

/**
 * 事务上下文：参与者共享调用方的同一 PG connection/transaction。
 * 参与者不得独立提交、不得 REQUIRES_NEW（契约 transaction-ports.json：platform.TransactionRunner）。
 */
public interface DbTx {

    Connection connection();
}
