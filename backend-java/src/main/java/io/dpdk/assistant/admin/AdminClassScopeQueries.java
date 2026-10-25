package io.dpdk.assistant.admin;

import io.dpdk.assistant.admin.AdminUserRepository.UserRow;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AdminClassScopePort 实现：范围判定复用 {@link AdminTreeRules}，
 * 主体清单复用 {@link AdminUserRepository}（与 Python UserService.list_users 同 SQL）。
 */
@Component
public class AdminClassScopeQueries implements AdminClassScopePort {

    private final AuthSettingsProvider settings;
    private final AdminUserRepository users;

    public AdminClassScopeQueries(AuthSettingsProvider settings, AdminUserRepository users) {
        this.settings = settings;
        this.users = users;
    }

    @Override
    public Optional<String> scopeCreator(Principal principal) {
        return Optional.ofNullable(AdminTreeRules.scopeCreator(settings, principal));
    }

    @Override
    public List<String> studentIds(String scopeCreator) {
        if (scopeCreator == null) {
            throw new IllegalArgumentException("scopeCreator 不能为空（不限范围由消费方自行处理）");
        }
        return users.listUsers(scopeCreator).stream().map(UserRow::id).toList();
    }

    @Override
    public List<String> visibleSubjects(String scopeCreator) {
        if (scopeCreator == null) {
            throw new IllegalArgumentException("scopeCreator 不能为空（不限范围由消费方自行处理）");
        }
        List<String> out = new ArrayList<>();
        users.getUser(scopeCreator).ifPresent(row -> out.add(row.id()));
        users.listUsers(scopeCreator).forEach(row -> out.add(row.id()));
        return List.copyOf(out);
    }
}
