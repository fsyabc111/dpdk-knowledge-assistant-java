package io.dpdk.assistant.contracts.platform;

import java.util.function.Function;

/**
 * 事务执行端口（契约 transaction-ports.json：platform.TransactionRunner）。
 *
 * required 语义：整笔提交或整笔回滚；work 抛异常即回滚。
 * 事务内不得跨模型/MCP/网络 await（受理事务不跨越外部调用，AGENTS 实施规则）。
 */
public interface TransactionRunner {

    <T> T required(Function<DbTx, T> work);
}
