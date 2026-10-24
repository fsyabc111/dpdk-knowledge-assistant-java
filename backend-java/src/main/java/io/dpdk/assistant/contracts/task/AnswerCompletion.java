package io.dpdk.assistant.contracts.task;

/**
 * 终态提交端口（owner J3-01；契约 transaction-ports.json：task.AnswerCompletion）。
 *
 * pending→终态条件更新、assistant message、ranked citations、issue(若需要)、
 * session touch、删除临时输入同一事务；重复终态拒绝；返回前持久化成功。
 */
public interface AnswerCompletion {

    CompletedAnswer finish(AnswerCompletionInput input);
}
