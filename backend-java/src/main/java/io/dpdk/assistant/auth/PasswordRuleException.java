package io.dpdk.assistant.auth;

/**
 * 新口令规则不满足（对应 Python user_service._validate_new_password 抛的 ValueError）。
 *
 * detail 文案逐字对齐 Python；端点层转 400 {"detail": str(exc)}。
 * 重置令牌消费路径吞掉本异常统一 400 invalid or expired reset token（不区分原因）。
 */
class PasswordRuleException extends RuntimeException {

    PasswordRuleException(String detail) {
        super(detail);
    }
}
