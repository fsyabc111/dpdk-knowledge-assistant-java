package io.dpdk.assistant.admin;

import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.Principal;
import java.util.Set;

/**
 * 树状层级管理规则（纯函数，对齐 admin.py 与 user_service.UserService.can_manage）。
 *
 * 语义见 evidence/J2-02/permission-matrix.md：
 * - leader 角色或静态应急 token 主体 → leader-like（树不受限）；
 * - 老师（admin）只管 created_by=自己 且 roles ⊆ {viewer}（非空）的目标；
 * - 删除路径的 leader 判定只认 actor.roles（静态 token 主体无 users 行，
 *   密码校验已天然挡住），与 admin.py `_delete_users_sync` 的口径一致。
 */
public final class AdminTreeRules {

    /** user_service.MANAGER_ROLES：可进入管理面板的角色。 */
    public static final Set<String> MANAGER_ROLES = Set.of("admin", "leader");

    /** user_service.ALLOWED_ROLES（服务端 roles 子集校验）。 */
    public static final Set<String> ALLOWED_ROLES = AuthServerSettings.ALLOWED_ROLES;

    /** 排序后的 ALLOWED_ROLES（ValueError 文案里的 Python sorted(list) repr 输出，含引号）。 */
    public static final String ALLOWED_ROLES_SORTED = "['admin', 'leader', 'operator', 'viewer']";

    private AdminTreeRules() {
    }

    /** 进组门禁：account 模式且 roles∩MANAGER_ROLES 非空（否则端点层 404）。 */
    public static boolean isManager(AuthSettingsProvider settings, Principal principal) {
        return settings.current().accountMode()
                && principal.roles().stream().anyMatch(MANAGER_ROLES::contains);
    }

    /** 组长或不受树约束的静态应急 token 主体（admin.py _is_leader_like）。 */
    public static boolean isLeaderLike(AuthSettingsProvider settings, Principal principal) {
        if (principal.roles().contains("leader")) {
            return true;
        }
        AuthServerSettings current = settings.current();
        return current.staticTokens().values().stream()
                .anyMatch(p -> p.subject().equals(principal.subject()));
    }

    /** 老师的作用域=自己；leader-like 返回 null 表示不限（admin.py _scope_creator）。 */
    public static String scopeCreator(AuthSettingsProvider settings, Principal principal) {
        return isLeaderLike(settings, principal) ? null : principal.subject();
    }

    /**
     * 树状管理判定（UserService.can_manage）：leader 管；admin 只管自建纯 viewer 学生。
     * 静态 token 主体不进入本判定（端点层按 leader-like 放行）。
     */
    public static boolean canManage(Principal actor, String targetCreatedBy,
                                    java.util.List<String> targetRoles) {
        if (actor.roles().contains("leader")) {
            return true;
        }
        if (!actor.roles().contains("admin")) {
            return false;
        }
        if (targetCreatedBy == null || !targetCreatedBy.equals(actor.subject())) {
            return false;
        }
        return !targetRoles.isEmpty() && targetRoles.stream().allMatch("viewer"::equals);
    }

    /** 删除路径的越权判定：只认 actor.roles 的 leader（不认静态 token）。 */
    public static boolean canDelete(Principal actor, String targetCreatedBy,
                                    java.util.List<String> targetRoles) {
        return actor.roles().contains("leader") || canManage(actor, targetCreatedBy, targetRoles);
    }

    /** roles JSON 文本 → 与 MANAGER_ROLES 的交集（空集=非管理角色）。 */
    public static java.util.Set<String> parseManagerRoles(String rolesJson) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String role : AdminUserRepository.parseRoles(rolesJson)) {
            if (MANAGER_ROLES.contains(role)) {
                out.add(role);
            }
        }
        return out;
    }
}
