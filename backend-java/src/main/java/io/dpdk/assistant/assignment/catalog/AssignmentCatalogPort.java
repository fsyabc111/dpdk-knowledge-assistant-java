package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;
import java.util.Optional;

/**
 * 作业目录读取端口（任务卡实现步骤 2，供 J2-04 正式提交消费）。
 *
 * 两种分值口径的显式分界：
 * - 受理口径：{@link #submissionSnapshot} 单条 SQL 读取受理时刻作业 + 目标协议项
 *   points（含可见性：status=active 且 scope/班级匹配，否则 Optional.empty）。
 *   J2-04 用它构造 accepted_assignment（points/scope/created_by/deadline/
 *   assignment_updated_at/accepted_at），正式提交响应用受理 points 折算。
 * - 汇总口径：{@link #getAssignment} 返回当前作业定义（含当前 items），成绩表
 *   （J2-03 report）与讲解上下文按当前分值动态折算；作业在受理后修改可产生
 *   预期差异（验收 E）。
 * {@link #bestScore} 供提交响应 best_earned（多次提交取最高）。
 */
public interface AssignmentCatalogPort {

    /** 受理快照；不可见（不存在/撤回/别班）返回 empty，协议不属于该作业 points=null。 */
    Optional<SubmissionSnapshot> submissionSnapshot(String assignmentId, String studentCreatedBy,
            String protocol);

    /** 当前作业定义（含当前协议项）；不存在返回 empty。 */
    Optional<AssignmentRow> getAssignment(String assignmentId);

    /** 该学生此作业此协议历史最高分（int 语义，同 MAX(score)）；无提交返回 empty。 */
    Optional<Integer> bestScore(String assignmentId, String userId, String protocol);

    /** 截止判定：now > deadline（Python `is_past_deadline`，naive 库值按 UTC）。 */
    boolean isPastDeadline(SubmissionSnapshot snapshot, Instant now);
}
