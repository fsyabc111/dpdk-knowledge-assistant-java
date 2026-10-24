package io.dpdk.assistant.contracts.identity;

/**
 * 身份解析端口（owner J2-01；契约 transaction-ports.json：identity.PrincipalResolver）。
 *
 * local/token/account 三模式；静态 token 优先；401 带 WWW-Authenticate；
 * 只保持 Python 四字段 wire 行为（subject/roles/dailyQuota(0=不限)/mustChangePassword）。
 */
public interface PrincipalResolver {

    Principal resolve(RequestHeaders headers, AuthSettings settings);
}
