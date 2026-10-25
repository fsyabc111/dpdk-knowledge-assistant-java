package io.dpdk.assistant.agent.port;

import java.util.Map;

/**
 * 任务评测作业账本端口（rag_task_jobs，owner J3-01；本包内为冻结接口的轻量替身）。
 *
 * <p>签名对位 backend/app/services/rag_issue_service.py 的
 * find_job / reserve_grading_job / finish_job / record_job / update_task_source。
 * 语义（冻结）：
 * <ul>
 *   <li>reserve：首次登记 job 与评测日配额同事务；同 (task_id, job_id) 已登记
 *       不再扣第二次；quota=0 不扣；配额超限抛 {@link QuotaExceededException}。</li>
 *   <li>finish：写入 status='finished' 与 result_json。</li>
 *   <li>updateTaskSource：只写 source_text/source_origin 两列（model_args 首评回填）。</li>
 * </ul>
 * 任何数据库故障抛 RuntimeException → grade_plugin 转 RetryableTaskFailure
 * （任务路径账本失败阻断评测，不降级为无账本执行）。
 */
public interface TaskJobLedgerPort {

    /** 按 (task_id, protocol, code_hash) 找账本行；没有返回 empty。行字段含 job_id/status/result_json。 */
    Map<String, Object> findJob(String taskId, String protocol, String codeHash);

    /** 登记评测作业；同 (task_id, job_id) 幂等，重复登记不产生第二行。 */
    void recordJob(String taskId, String jobId, String tool, String protocol, String codeHash);

    /** 首次登记 job 与评测日配额同事务；已登记 job 不再扣第二次。 */
    void reserveGradingJob(String taskId, String jobId, String protocol, String codeHash,
                           String userId, int quota);

    /** 写终态结果（status=finished + result_json）。 */
    void finishJob(String jobId, Map<String, Object> result);

    /** 回填执行源两列（grade_plugin 的 model_args 首评路径用）。 */
    void updateTaskSource(String taskId, String sourceText, String sourceOrigin);
}
