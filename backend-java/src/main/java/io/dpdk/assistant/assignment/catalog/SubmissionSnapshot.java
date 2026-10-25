package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;

/**
 * 受理快照（对齐 Python `AssignmentService.submission_snapshot` 的单条 SQL 返回）。
 *
 * J2-04 受理分值口径：points 是受理时刻该协议项的分值（无该协议项/不可见/已撤回
 * → 整体为空或 points=null）；J2-04 落库的 accepted_assignment 以本快照为准。
 * 与汇总端（report）的动态折算口径的边界见 {@link AssignmentCatalogPort}。
 */
public record SubmissionSnapshot(
        String id,
        String title,
        String createdBy,
        String scope,
        Instant deadline,
        Instant updatedAt,
        /** 目标协议项当前分值；协议不属于该作业（LEFT JOIN 未命中）为 null。 */
        Integer points) {
}
