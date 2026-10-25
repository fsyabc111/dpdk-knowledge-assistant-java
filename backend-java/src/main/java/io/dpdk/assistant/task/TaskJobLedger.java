package io.dpdk.assistant.task;

import java.util.List;

/**
 * 任务执行体可用的作业账本端口（grade_plugin 接续/复用；对应 agent/tools.py 消费面）。
 *
 * 实现委托 {@link TaskStore}（task 包自有仓储），Agent 不自行拼业务 SQL。
 */
public interface TaskJobLedger {

    /** reserve_grading_job：首次登记 job 与评测日配额同事务；已登记返回 false 不重扣。 */
    boolean reserveGradingJob(String taskId, String jobId, String protocol, String codeHash,
                              String userId, int quota, String utcDay);

    /** record_job：同 (task_id, job_id) 幂等登记（非配额路径）。 */
    void recordJob(String taskId, String jobId, String tool, String protocol, String codeHash);

    /** finish_job：harness 结果写回（submitted→finished）。 */
    void finishJob(String jobId, String resultJson);

    /** find_job：按 protocol/code_hash 检索（null=IS NULL）。 */
    java.util.Optional<TaskJobRecord> findJob(String taskId, String protocol, String codeHash);

    /** update_task_source：model_args 首评回填执行源。 */
    void updateTaskSource(String taskId, String sourceText, String sourceOrigin);

    /** load_task_inputs：恢复上下文装配与 fallback_source 读取。 */
    java.util.Optional<TaskInputRecord> loadInputs(String taskId);

    /** 成绩与账本标志同事务落库（contracts.grading.TaskGradeParticipant 语义）。 */
    io.dpdk.assistant.contracts.grading.TaskGradeResult recordTaskGrade(
            String taskId, String jobId, String protocol, String codeHash,
            int score, int passed, int total, String resultsJson);

    static TaskJobLedger over(TaskStore store, io.dpdk.assistant.contracts.platform.ClockPort clock,
                              io.dpdk.assistant.contracts.identity.AccountWriteGuard guard,
                              io.dpdk.assistant.contracts.platform.TransactionRunner runner) {
        return new TaskJobLedger() {
            private final TaskGradeLedger grades = new TaskGradeLedger(store, guard, runner, clock);

            @Override
            public boolean reserveGradingJob(String taskId, String jobId, String protocol,
                                             String codeHash, String userId, int quota, String utcDay) {
                return store.reserveGradingJob(taskId, jobId, protocol, codeHash, userId, quota, utcDay);
            }

            @Override
            public void recordJob(String taskId, String jobId, String tool, String protocol, String codeHash) {
                store.recordJob(taskId, jobId, tool, protocol, codeHash);
            }

            @Override
            public void finishJob(String jobId, String resultJson) {
                store.finishJob(jobId, resultJson);
            }

            @Override
            public java.util.Optional<TaskJobRecord> findJob(String taskId, String protocol, String codeHash) {
                return store.findJob(taskId, protocol, codeHash);
            }

            @Override
            public void updateTaskSource(String taskId, String sourceText, String sourceOrigin) {
                store.updateTaskSource(taskId, sourceText, sourceOrigin);
            }

            @Override
            public java.util.Optional<TaskInputRecord> loadInputs(String taskId) {
                return store.loadInputs(taskId);
            }

            @Override
            public io.dpdk.assistant.contracts.grading.TaskGradeResult recordTaskGrade(
                    String taskId, String jobId, String protocol, String codeHash,
                    int score, int passed, int total, String resultsJson) {
                return grades.recordTaskGrade(new io.dpdk.assistant.contracts.grading.TaskGradeInput(
                        taskId, jobId, protocol, codeHash, score, passed, total, resultsJson));
            }
        };
    }

    /** 供实现侧排序/收集使用的稳定空列表工具（避免各执行体自建）。 */
    static List<CitationData> noCitations() {
        return List.of();
    }
}
