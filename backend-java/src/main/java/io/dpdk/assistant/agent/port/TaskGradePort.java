package io.dpdk.assistant.agent.port;

import io.dpdk.assistant.contracts.grading.TaskGradeInput;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;

/**
 * 任务成绩落库端口（冻结契约 grading.TaskGradeParticipant 的消费侧接口）。
 *
 * <p>签名取自 transaction-ports.json（owner J2-04）：
 * {@code TaskGradeResult recordTaskGrade(TaskGradeInput input)}，返回
 * persisted | legacy_unlinked | cancelled。语义：由 grading transaction 锁
 * enabled user、pending task 和 job；成绩 insert 与 submission_done 同提交；
 * task_job_id 部分唯一索引防重复；0014 以前已标完成无关联成绩返回
 * legacy_unlinked 不猜测补写。集成期接 J2-04 真实实现；测试用内存替身，
 * 故障注点覆盖 reserve/提交/finish/成绩落库间的崩溃窗口。
 */
public interface TaskGradePort {

    TaskGradeResult recordTaskGrade(TaskGradeInput input);
}
