package io.dpdk.assistant.auth;

import java.util.List;
import java.util.Optional;

/**
 * J2-02 协作端口：忘记密码申请的列表/审批/驳回占位（任务卡实现步骤 3，HTTP 层归 J2-02）。
 *
 * 存储与签发语义（sha256 摘要、30 分钟有效期、一次性消费）由 J2-01 持有；
 * J2-02 的 /api/admin/password-reset-requests 端点挂接本端口。
 */
public interface PasswordResetApprovalPort {

    /** pending（或指定 status）申请单；scopeCreator=null 全部，否则树隔离限定归属。 */
    List<ResetRequestRow> listResetRequests(String status, String scopeCreator);

    /** 审批通过：返回一次性明文令牌与过期 ISO；申请不存在/非 pending/用户不存在/越界返回 empty。 */
    Optional<ResetTokenIssue> approveResetRequest(String requestId, String adminId, String scopeCreator);

    /** 驳回申请：pending → rejected；不存在/越界/非 pending 返回 false。 */
    boolean rejectResetRequest(String requestId, String adminId, String scopeCreator);

    /** 申请单行（id/user_id/created_at，created_at 为 isoformat 字符串）。 */
    record ResetRequestRow(String id, String userId, String createdAt) {
    }

    /** 审批签发的一次性令牌（明文仅此一次返回）与过期时间 ISO。 */
    record ResetTokenIssue(String token, String expiresAtIso) {
    }
}
