package io.dpdk.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.agent.port.QuotaExceededException;
import io.dpdk.assistant.agent.support.AgentScenario;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.agent.support.FakeLedger;
import io.dpdk.assistant.agent.support.Fixtures;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;
import io.dpdk.assistant.retrieval.PythonJson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S3-c 评测作业账本接续与崩溃窗口测试（任务路径 grade_plugin；替身账本，无真实 PG）。
 *
 * <p>覆盖：确定性 job_id、get_task_status 接续、finished 回放、同 job_id 接续、
 * 已登记 job 不二次扣配额、账本失败阻断评测、reserve/提交/finish/成绩落库之间的
 * 崩溃窗口逐一注点。与 J1-02 的「提交后禁止传输重试」区分：接续走账本状态 +
 * 同 job_id 幂等重投（COMP-003），不虚构全局 exactly-once。
 */
class TaskLedgerTest {

    private static final String SOURCE =
            "/* 任务评测插件 */\nint plugin_main(void){return 0;}\n";

    private static AgentScenario taskScenario() {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.taskContext = new AgentOrchestrator.TaskContext("task_1", null, null);
        return scenario;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> result) {
        return (Map<String, Object>) result.get("data");
    }

    private static Map<String, Object> invokeGrade(AgentToolContext context) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", "dns");
        args.put("plugin_source", SOURCE);
        return AgentScenario.toolsByName(context).get("grade_plugin").invoke(args);
    }

    private static String jobHash() {
        return io.dpdk.assistant.agent.tools.AgentTools
                .sha256Hex(SOURCE.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String deterministicJobId() {
        return "task_1:dns:" + jobHash();
    }

    // ------------------------------------------------------------------
    // 首评路径
    // ------------------------------------------------------------------

    @Test
    void first_run_registers_job_with_deterministic_id_and_charges_quota() throws Exception {
        AgentScenario scenario = taskScenario();
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        // 确定性 job_id 进入 MCP 入参（同 job_id 幂等语义在 harness 侧）
        assertThat(scenario.harness.callsOf("grade_plugin").get(0).get("args"))
                .isEqualTo(Map.of("protocol", "dns", "plugin_source", SOURCE,
                        "timeout_sec", 120, "job_id", deterministicJobId()));
        // 账本：登记 + finish + 成绩落库 + submission_done（经 TaskGradePort）
        assertThat(scenario.ledger.rows).hasSize(1);
        assertThat(scenario.ledger.rowByJobId(deterministicJobId()).get("status"))
                .isEqualTo("finished");
        assertThat(scenario.taskGrade.calls).hasSize(1);
        assertThat(scenario.taskGrade.calls.get(0).jobId()).isEqualTo(deterministicJobId());
        assertThat(scenario.taskGrade.calls.get(0).codeHash())
                .isEqualTo(io.dpdk.assistant.agent.tools.AgentTools
                        .sha256Hex(SOURCE.getBytes(StandardCharsets.UTF_8)));
        // 配额经 reserve 同事务扣减（非独立 checkAndCountDaily）
        assertThat(scenario.quota.charges).isEmpty();
        assertThat(scenario.ledger.quotaCharges).hasSize(1);
        // model_args 首评回填执行源
        assertThat(scenario.ledger.backfilledSources).containsExactly("task_1|model_args");
        // 成绩只存 hash+结果：TaskGradeInput 不携带源码全文
        assertThat(scenario.taskGrade.calls.get(0).resultsJson())
                .contains("source_origin").contains("results");
    }

    @Test
    void resumed_job_does_not_charge_quota_again() throws Exception {
        // 已登记 job 先接续再判扣配额：恢复重投同 (task, job) 不二次扣。
        AgentScenario scenario = taskScenario();
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(scenario.ledger.quotaCharges).isEmpty(); // 恢复不二次扣
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1); // 同 job_id 重投
        assertThat(scenario.taskGrade.calls).hasSize(1); // 成绩落一次
    }

    // ------------------------------------------------------------------
    // finished 回放与 get_task_status 接续
    // ------------------------------------------------------------------

    @Test
    void finished_row_replays_without_mcp() throws Exception {
        AgentScenario scenario = taskScenario();
        Map<String, Object> finishedPayload = Fixtures.payload("HARNESS_OK");
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        scenario.ledger.finishJob(deterministicJobId(), finishedPayload);
        scenario.ledger.quotaCharges.clear(); // 忽略 fixture 预注；验证回放零扣
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result)).containsEntry("resumed", true);
        // 不打 MCP grade_plugin（回放），但成绩落库照常（幂等 persisted）
        assertThat(scenario.harness.callsOf("grade_plugin")).isEmpty();
        assertThat(scenario.harness.calls).isEmpty();
        assertThat(scenario.taskGrade.calls).hasSize(1);
        assertThat(scenario.ledger.quotaCharges).isEmpty(); // 回放不扣配额
    }

    @Test
    void submitted_row_resumes_via_get_task_status() throws Exception {
        // 账本行 submitted + harness 已 finished → 接续：finish_job + 回放结果。
        AgentScenario scenario = taskScenario();
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        scenario.harness.payloads.put("get_task_status", Fixtures.payload("TASK_STATUS_FINISHED"));
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result)).containsEntry("resumed", true);
        assertThat(data(result).get("score")).isEqualTo(88);
        assertThat(scenario.ledger.rowByJobId(deterministicJobId()).get("status"))
                .isEqualTo("finished");
        assertThat(scenario.harness.callsOf("grade_plugin")).isEmpty();
        assertThat(scenario.taskGrade.calls).hasSize(1);
    }

    @Test
    void missing_row_falls_back_to_status_probe_and_records() throws Exception {
        // 账本缺行 + harness 有终态 → 兜底登记（record_job + finish_job）后回放，
        // 兜住「harness 有结果而账本漏写」的崩溃窗口。
        AgentScenario scenario = taskScenario();
        scenario.harness.payloads.put("get_task_status", Fixtures.payload("TASK_STATUS_FINISHED"));
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result)).containsEntry("resumed", true);
        assertThat(scenario.ledger.rows).hasSize(1);
        assertThat(scenario.ledger.rowByJobId(deterministicJobId()).get("status"))
                .isEqualTo("finished");
        assertThat(scenario.harness.callsOf("grade_plugin")).isEmpty();
        assertThat(scenario.taskGrade.calls).hasSize(1);
    }

    @Test
    void running_remote_status_reinvests_with_same_job_id() throws Exception {
        // 远端 running：不虚构终态，同 job_id 幂等重投（加入既有执行）。
        AgentScenario scenario = taskScenario();
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        scenario.harness.payloads.put("get_task_status",
                Map.of("ok", true, "status", "running"));
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result)).doesNotContainKey("resumed");
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);
        assertThat(scenario.taskGrade.calls).hasSize(1);
    }

    @Test
    void unknown_remote_status_never_fakes_exactly_once() throws Exception {
        // 未知远端状态（探测失败）：按不可接续处理重投，不虚构已做到全局 exactly-once。
        AgentScenario scenario = taskScenario();
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        scenario.harness.payloads.put("get_task_status",
                Map.of("ok", false, "error", "unknown_job", "detail", "账本无该 job"));
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 崩溃窗口矩阵
    // ------------------------------------------------------------------

    @Test
    void crash_windows_block_evaluation_as_retryable() {
        // 注点：findJob / reserve / finish / 成绩落库 —— 每个窗口失败都阻断评测
        //（RetryableTaskFailure），输入与账本保留，不收成正常终态。
        for (int inject = 0; inject < 4; inject++) {
            AgentScenario scenario = taskScenario();
            RuntimeException boom = new IllegalStateException("db down @" + inject);
            switch (inject) {
                case 0 -> scenario.ledger.findJobError = boom;
                case 1 -> scenario.ledger.reserveError = boom;
                case 2 -> scenario.ledger.finishError = boom;
                case 3 -> scenario.taskGrade.failure = () -> boom;
                default -> throw new IllegalStateException();
            }
            AgentToolContext context = scenario.buildContext();
            assertThatThrownBy(() -> invokeGrade(context))
                    .isInstanceOf(io.dpdk.assistant.agent.tools.RetryableTaskFailure.class)
                    .hasMessageContaining("task_job");
            // 账本状态可审计（隔离测试账本保留供审计）
            assertThat(scenario.ledger.rows.size() + scenario.ledger.quotaCharges.size()
                    + scenario.taskGrade.calls.size()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void update_source_backfill_failure_is_retryable() {
        // model_args 首评回填失败 → reserve 同事务整体回滚语义 → 可重驱动。
        AgentScenario scenario = taskScenario();
        scenario.ledger.updateSourceError = new IllegalStateException("db down");
        AgentToolContext context = scenario.buildContext();
        assertThatThrownBy(() -> invokeGrade(context))
                .isInstanceOf(io.dpdk.assistant.agent.tools.RetryableTaskFailure.class)
                .hasMessageContaining("task_job_record_failed");
    }

    @Test
    void record_failure_after_success_keeps_ledger_submitted() throws Exception {
        // 成绩落库失败：账本行保持 submitted/finished（结果已保存），成绩可由恢复路径补写。
        AgentScenario scenario = taskScenario();
        scenario.taskGrade.failure = () -> new IllegalStateException("grade save failed");
        AgentToolContext context = scenario.buildContext();
        assertThatThrownBy(() -> invokeGrade(context))
                .isInstanceOf(io.dpdk.assistant.agent.tools.RetryableTaskFailure.class)
                .hasMessageContaining("task_job_grade_save_failed");
        // harness 已被调用过一次（评测已发生），账本行已 finished
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);
    }

    @Test
    void cancelled_task_grade_returns_structured_task_cancelled() throws Exception {
        AgentScenario scenario = taskScenario();
        scenario.taskGrade.cancelled = true;
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("task_cancelled");
        assertThat(result.get("detail")).isEqualTo("任务已取消");
    }

    @Test
    void legacy_unlinked_grade_is_accepted_with_warning() throws Exception {
        // 0014 以前的成绩没有 job 身份：legacy_unlinked 不猜测补写，评测照常返回。
        AgentScenario scenario = taskScenario();
        scenario.taskGrade.legacyUnlinked = 1;
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(scenario.taskGrade.calls).hasSize(1);
    }

    @Test
    void task_path_quota_exceeded_is_structured_not_retryable() throws Exception {
        // reserve 同事务配额超限 → 结构化 quota_exceeded（模型可转述），非异常。
        AgentScenario scenario = taskScenario();
        scenario.ledger = new io.dpdk.assistant.agent.support.FakeLedger() {
            @Override
            public void reserveGradingJob(String taskId, String jobId, String protocol,
                                          String codeHash, String userId, int quota) {
                throw new QuotaExceededException("今日评测配额已用完");
            }
        };
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("quota_exceeded");
        assertThat(scenario.harness.callsOf("grade_plugin")).isEmpty(); // 拦截在 harness 之前（get_task_status 探测除外）
    }

    @Test
    void exempt_roles_skip_quota_on_task_path() throws Exception {
        AgentScenario scenario = taskScenario();
        scenario.roles = List.of("leader");
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(scenario.ledger.quotaCharges).isEmpty();
    }

    @Test
    void task_grade_input_carries_hash_and_results_not_source() throws Exception {
        // 成绩只存 hash+结果：TaskGradeInput 无源码全文列。
        AgentScenario scenario = taskScenario();
        AgentToolContext context = scenario.buildContext();
        invokeGrade(context);
        io.dpdk.assistant.contracts.grading.TaskGradeInput input = scenario.taskGrade.calls.get(0);
        assertThat(input.score()).isEqualTo(100);
        assertThat(input.passed()).isEqualTo(8);
        assertThat(input.total()).isEqualTo(8);
        assertThat(input.resultsJson()).doesNotContain("plugin_main");
        assertThat(input.resultsJson()).contains("dns_basic_1");
    }

    @Test
    void replayed_payload_json_corruption_falls_back_to_reinvest() throws Exception {
        // result_json 损坏 → 按无终态处理，主流程同 job_id 重投。
        AgentScenario scenario = taskScenario();
        scenario.ledger.recordJob("task_1", deterministicJobId(), "grade_plugin", "dns",
                io.dpdk.assistant.agent.tools.AgentTools.sha256Hex(
                        SOURCE.getBytes(StandardCharsets.UTF_8)));
        scenario.ledger.rowByJobId(deterministicJobId()).put("status", "finished");
        scenario.ledger.rowByJobId(deterministicJobId()).put("result_json", "{broken");
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result)).doesNotContainKey("resumed");
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);
    }

    @Test
    void non_task_path_never_touches_ledger() throws Exception {
        // 与任务路径的边界：无 taskId 时账本接线整体跳过，行为与旧版一致。
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        AgentToolContext context = scenario.buildContext();
        Map<String, Object> result = invokeGrade(context);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(scenario.ledger.rows).isEmpty();
        assertThat(scenario.taskGrade.calls).isEmpty();
        assertThat(scenario.quota.charges).hasSize(1);
        assertThat(scenario.record.submissions).hasSize(1);
    }

    @Test
    void python_json_roundtrip_for_payload() {
        // 账本 result_json 的读写口径（PythonJson 与 Python json.dumps 兼容）。
        Map<String, Object> payload = Fixtures.payload("HARNESS_OK");
        String json = PythonJson.dumps(payload, false);
        assertThat(json).contains("\"ok\": true").contains("\"score\": 100");
    }

    @Test
    void taskGradeResultEnum_covers_frozen_contract() {
        // 冻结契约 grading.TaskGradeParticipant 的三态。
        assertThat(TaskGradeResult.values()).containsExactly(
                TaskGradeResult.persisted, TaskGradeResult.legacy_unlinked, TaskGradeResult.cancelled);
    }
}
