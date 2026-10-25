package io.dpdk.assistant.agent.tools;

import java.util.Map;

/**
 * Agent 工具接口（对位 langchain @tool 的最小消费面）。
 *
 * <p>统一约定：返回 {"ok": true, "data": ...} 或
 * {"ok": false, "error": <code>, "detail": <可读原因>}，不向外抛异常——工具的
 * 失败应当变成模型可读、可转述给学生的信息，而不是请求级 500。
 * 例外：账本/成绩持久化失败抛 {@link RetryableTaskFailure}，由产品任务保留
 * 输入并重驱动。
 */
public interface AgentTool {

    String name();

    String description();

    /** args 为模型给出的参数 Map；实现自行做必填/类型容错（对位 Python 签名解包）。 */
    Map<String, Object> invoke(Map<String, Object> args) throws Exception;
}
