package io.dpdk.assistant.contracts.grading;

/**
 * 任务评测成绩参与端口（owner J2-04；契约 transaction-ports.json：grading.TaskGradeParticipant）。
 *
 * 由 grading 事务锁 enabled user、pending task 和 job；成绩 insert 与 submission_done
 * 同提交；task_job_id 部分唯一索引防重复；0014 以前已标完成无关联成绩返回
 * legacy_unlinked，不猜测补写。
 */
public interface TaskGradeParticipant {

    TaskGradeResult recordTaskGrade(TaskGradeInput input);
}
