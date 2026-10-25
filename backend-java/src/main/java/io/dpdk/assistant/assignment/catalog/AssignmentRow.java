package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;
import java.util.List;

/**
 * assignments 行 + 关联 assignment_items（对齐 Python `_assignment_row` 装配）。
 *
 * 时间列存 Instant；HTTP 渲染层做 Python str(datetime) 格式化（空格分隔、
 * 微秒为零省略、+00:00，golden 已冻结该形态；见 catalog.PythonDateTime）。
 * items 恒按 protocol ASC（`_load_items` 的 ORDER BY protocol）。
 */
public record AssignmentRow(
        String id,
        String title,
        String description,
        String createdBy,
        String scope,
        Instant deadline,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<ItemRow> items) {

    /** assignment_items 行（protocol + points）。 */
    public record ItemRow(String protocol, int points) {
    }
}
