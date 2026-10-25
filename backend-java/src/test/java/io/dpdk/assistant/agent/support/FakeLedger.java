package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.agent.port.QuotaExceededException;
import io.dpdk.assistant.agent.port.TaskJobLedgerPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存假任务账本（J3-01 真实实现就绪前的替身测试桩）。
 *
 * <p>行字段与 rag_task_jobs 消费面对齐：job_id/status/result_json/protocol/code_hash。
 * 故障注点（find/reserve/finish/record/updateSource 各自可注入 RuntimeException）
 * 覆盖 reserve/提交/finish/成绩落库之间的崩溃窗口。
 */
public final class FakeLedger implements TaskJobLedgerPort {

    /** 行 = {job_id, status, result_json, protocol, code_hash, task_id}。 */
    public final List<Map<String, Object>> rows = new ArrayList<>();
    public final List<String> quotaCharges = new ArrayList<>();
    public RuntimeException findJobError;
    public RuntimeException reserveError;
    public RuntimeException finishError;
    public RuntimeException recordError;
    public RuntimeException updateSourceError;
    public final List<String> backfilledSources = new ArrayList<>();

    @Override
    public Map<String, Object> findJob(String taskId, String protocol, String codeHash) {
        if (findJobError != null) {
            throw findJobError;
        }
        for (Map<String, Object> row : rows) {
            if (taskId.equals(row.get("task_id")) && protocol.equals(row.get("protocol"))
                    && codeHash.equals(row.get("code_hash"))) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void recordJob(String taskId, String jobId, String tool, String protocol,
                          String codeHash) {
        if (recordError != null) {
            throw recordError;
        }
        for (Map<String, Object> row : rows) {
            if (taskId.equals(row.get("task_id")) && jobId.equals(row.get("job_id"))) {
                return; // 幂等
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_id", taskId);
        row.put("job_id", jobId);
        row.put("tool", tool);
        row.put("protocol", protocol);
        row.put("code_hash", codeHash);
        row.put("status", "submitted");
        row.put("result_json", null);
        rows.add(row);
    }

    @Override
    public void reserveGradingJob(String taskId, String jobId, String protocol, String codeHash,
                                  String userId, int quota) {
        if (reserveError != null) {
            throw reserveError;
        }
        for (Map<String, Object> row : rows) {
            if (taskId.equals(row.get("task_id")) && jobId.equals(row.get("job_id"))) {
                return; // 已登记不二次扣
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_id", taskId);
        row.put("job_id", jobId);
        row.put("tool", "grade_plugin");
        row.put("protocol", protocol);
        row.put("code_hash", codeHash);
        row.put("status", "submitted");
        row.put("result_json", null);
        rows.add(row);
        if (quota > 0) {
            quotaCharges.add(userId);
        }
    }

    @Override
    public void finishJob(String jobId, Map<String, Object> result) {
        if (finishError != null) {
            throw finishError;
        }
        for (Map<String, Object> row : rows) {
            if (jobId.equals(row.get("job_id"))) {
                row.put("status", "finished");
                row.put("result_json", io.dpdk.assistant.retrieval.PythonJson.dumps(result, false));
                return;
            }
        }
        throw new IllegalStateException("finishJob: 账本缺行 " + jobId);
    }

    @Override
    public void updateTaskSource(String taskId, String sourceText, String sourceOrigin) {
        if (updateSourceError != null) {
            throw updateSourceError;
        }
        backfilledSources.add(taskId + "|" + sourceOrigin);
    }

    public Map<String, Object> rowByJobId(String jobId) {
        for (Map<String, Object> row : rows) {
            if (jobId.equals(row.get("job_id"))) {
                return row;
            }
        }
        return null;
    }

    public Map<String, Object> quotaSummary() {
        Map<String, Object> out = new HashMap<>();
        out.put("count", quotaCharges.size());
        return out;
    }
}
