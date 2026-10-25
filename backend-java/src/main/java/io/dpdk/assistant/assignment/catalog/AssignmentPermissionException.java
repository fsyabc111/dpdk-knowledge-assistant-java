package io.dpdk.assistant.assignment.catalog;

/** 作业修改/撤回权限失败（对齐 Python PermissionError「只有作业创建者或组长可以修改」→ 403）。 */
public class AssignmentPermissionException extends RuntimeException {

    public AssignmentPermissionException(String message) {
        super(message);
    }
}
