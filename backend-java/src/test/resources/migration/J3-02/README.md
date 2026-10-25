# J3-02 测试资源（迁移夹具）

本目录存放 J3-02（Agent 五工具与受控循环）的确定性测试夹具。

## harness-payloads.json

固定模型/harness 响应形状（对齐 Python `backend/tests/agent/golden_support.py` 的
常量）：HARNESS_OK / HARNESS_FAIL / LIST_PROTOCOLS_OK / LIST_CASES_DNS /
REFERENCE_DNS / TASK_STATUS_FINISHED。由
`io.dpdk.assistant.agent.support.Fixtures` 装载；键即 `Fixtures.payload("...")`。

约定：

- 只放确定性、无网络、无凭据的响应形状；不放模型原始输出或运行日志。
- 黄金场景的脚本化模型回复在 `AgentScenario`/`ScriptedChatModel` 的测试代码里
  组装（ToolCallSpec 结构比 JSON 更可读），本文件只承载 harness 载荷。
- 修改键名/形状须同步 `Fixtures` 与引用它的测试。
