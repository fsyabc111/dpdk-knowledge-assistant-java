package io.dpdk.assistant.contracts.identity;

import io.dpdk.assistant.contracts.platform.DbTx;

/**
 * 账号写入门禁端口（owner J2-01；契约 transaction-ports.json：identity.AccountWriteGuard）。
 *
 * 业务写入事务内 UPDATE users SET enabled=enabled ... RETURNING 锁账号；
 * 停号/删除与受理/记分按提交顺序裁决；静态无 users 行例外，但同名已停账号不可绕过。
 */
public interface AccountWriteGuard {

    boolean lockEnabled(DbTx tx, String subject);
}
