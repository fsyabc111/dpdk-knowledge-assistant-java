package io.dpdk.assistant.auth;

/**
 * J2-02 协作端口：管理员改密/停号触发的凭据立即失效（任务卡实现步骤 4）。
 *
 * admin/**（J2-02）通过本端口触发凭据失效；J3-01/J2-04 的停号写入裁决走
 * contracts.identity.AccountWriteGuard（账号行锁），token 撤销走本端口。
 */
public interface CredentialInvalidationPort {

    /** 管理员重置为初始密码（=学号）：must_change_password 置位、撤销全部 token。账号不存在返回 false。 */
    boolean resetPasswordToInitial(String userId);

    /**
     * 管理员设置新密码：must_change_password 置位（下次登录强制改密）、撤销全部 token。
     * 规则不满足抛 {@link PasswordRuleException}（J2-02 转 400 detail）；
     * 账号不存在返回 false（J2-02 转 404）。
     */
    boolean setPasswordByAdmin(String userId, String newPassword);

    /** 停号等场景撤销该账号全部 token（清空 resolve 缓存，立即生效）。 */
    void revokeUserTokens(String userId);
}
