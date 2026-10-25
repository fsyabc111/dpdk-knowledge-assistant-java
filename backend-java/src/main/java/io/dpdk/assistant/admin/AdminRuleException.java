package io.dpdk.assistant.admin;

/**
 * 业务规则违规（对应 Python UserService 抛出的 ValueError，路由层转 400
 * {"detail": str(exc)}；文案逐字对齐冻结源）。
 */
public class AdminRuleException extends RuntimeException {

    public AdminRuleException(String message) {
        super(message);
    }
}
