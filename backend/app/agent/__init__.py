"""LangGraph agent 编排层（docs/archive/2026-09-18/agent_upgrade_plan.md M1）。

- graph.py：planner/tools/finalize 状态图（无 checkpointer，D11）
- tools.py：kb_search / grade_plugin 两个工具，统一 {ok, data|error} 返回
- mcp_client.py：teaching_harness 的 streamable HTTP MCP 短连接客户端
- prompts.py：分层系统提示词（基础层 + 检测反馈层 + 红线层，§4.3）
- service.py：AgentOrchestrator，产品问答路径唯一编排实现
"""
