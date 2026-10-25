package io.dpdk.assistant.admin;

import io.dpdk.assistant.contracts.identity.Principal;
import java.util.List;
import java.util.Optional;

/**
 * 班级范围查询端口（任务卡实现步骤 4）：供作业（J2-03）、用量、问题工作台等
 * 消费方解析树隔离范围，避免各模块复制权限 SQL。
 *
 * 口径（usage.py `_is_leader_like` / grading.py `_scope_user_ids`）：
 * - leader 角色或静态应急 token 主体 → 不限（scopeCreator=empty）；
 * - 老师（admin）→ scopeCreator=自己；
 * - operator 在 grading 视图等同不限属于消费方角色门禁，不经本端口表达。
 *
 * 消费方须自行完成 404 门禁（roles∩MANAGER_ROLES / GRADING_VIEWER_ROLES），
 * 再以本端口得到的范围过滤自己的查询。
 */
public interface AdminClassScopePort {

    /** 树隔离范围创建者：leader-like → empty（不限）；老师 → 自己 subject。 */
    Optional<String> scopeCreator(Principal principal);

    /** created_by=scopeCreator 的学生 id（id ASC；grading 口径，不含本人）。 */
    List<String> studentIds(String scopeCreator);

    /** 老师视角可见主体 = 本人 + 自建学生（usage 口径，id ASC，本人行在前）。 */
    List<String> visibleSubjects(String scopeCreator);
}
