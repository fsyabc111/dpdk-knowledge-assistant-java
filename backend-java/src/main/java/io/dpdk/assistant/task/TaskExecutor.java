package io.dpdk.assistant.task;

import java.util.List;
import java.util.function.Consumer;

/**
 * 任务执行体端口（冻结接口，J3-02 的 Agent 循环实现它；Agent 未完成前测试用
 * 脚本化替身）。
 *
 * 语义（对齐 rag_product_service.run/_candidate）：
 * - 执行体通过 {@code emit} 发过程事件（status/tool_start/tool_end/...），事件由
 *   运行层落回放日志并扇出订阅者；执行体不得自行落库终态（finish 由运行层协调）；
 * - 中断（取消/超时）以线程 interrupt 传达，执行体应尽快以异常退出；
 * - 返回值是「候选答案 + 校验结论」，持久化经 AnswerCompletion 同事务完成。
 */
public interface TaskExecutor {

    /**
     * 执行一次问答/评测任务。
     *
     * @return 候选结论（answer/status/reason/citations/releaseId）
     * @throws Exception 失败原样抛出（运行层分类：中断/超时/一般失败）
     */
    TaskExecutionResult run(TaskExecutionContext context, Consumer<TaskEvent> emit) throws Exception;

    /** 执行上下文：受理/恢复装配后的完整输入（原始问题与寄存执行源在此）。 */
    record TaskExecutionContext(
            String taskId,
            String requestId,
            String sessionId,
            String subject,
            List<String> roles,
            String purpose,
            String question,
            String contextText,
            String attachmentName,
            String attachmentSuffix,
            String sourceText,
            String sourceOrigin) {
    }

    /** 执行结论（Verdict + citations + release 同形态）。 */
    record TaskExecutionResult(
            String answer,
            String status,
            String reason,
            List<CitationData> citations,
            String releaseId) {
    }
}
