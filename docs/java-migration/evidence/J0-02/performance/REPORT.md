# J0-02 Python 合成性能与恢复实测

**当前确认状态：用户已明确接受并冻结本组 P0 合成门槛，决策 U09 / P0-D15。** 权威数值与适用域见 [thresholds.json](../../../../../migration/contracts/thresholds.json)，用户确认见 [DECISIONS.md](../../../DECISIONS.md)。`threshold-candidates.json` 保留提出时历史，不再代表等待当前确认。

Python 正常 mix 已通过本组冻结合成门槛；这不表示 Java 候选或 P5 生产容量通过，也不表示真实模型/embedding/harness 质量已验证。Python 恢复现状保留 COMP003 已批准安全差异，未修改冻结源码。

基线 `80b29135faddd16d5e39fdbf0633291ea6a20489`；Python `3.11.15`；Apple M4，逻辑 CPU 10；PG 17.10 (Homebrew) / pgvector 0.8.4，schema `0015_explain_purpose`。

正常负载完整执行 3 轮，每轮预热 120s、测量 600s。60 合成学生，最多 10 个问答/讲解槽位；模型每调用 1s、检索 50ms、MCP 200ms。同进程真实 FastAPI/认证/产品服务/TaskService/LangGraph/PG；外部 I/O 为替身。测量不含 TCP/TLS/nginx/真实上游。

| 分组 | 测量 n | p50 ms | p95 ms | p99 ms | 异常/分母 |
|---|---:|---:|---:|---:|---:|
| login | 180 | 218.847 | 247.916 | 样本不足，不报 | 0/180 |
| assignment_read | 21600 | 11.02 | 30.137 | 74.395 | 0/21600 |
| formal_submit | 144 | 213.668 | 219.006 | 样本不足，不报 | 0/144 |
| chat | 8419 | 2100.598 | 2154.735 | 2195.212 | 0/8419 |
| explain_admission | 144 | 26.744 | 56.494 | 样本不足，不报 | 0/144 |
| explain_terminal | 144 | 2103.419 | 2154.977 | 样本不足，不报 | 0/144 |

| 轮次 | 含排空时长 s | chat p95 ms | 活动/等待峰值 | app RSS峰 MiB | app CPU峰 %一核 |
|---|---:|---:|---:|---:|---:|
| 1 | 602.090 | 2148.964 | 10/0 | 184.09 | 781.46 |
| 2 | 601.250 | 2152.039 | 10/0 | 184.55 | 787.79 |
| 3 | 602.077 | 2163.409 | 10/0 | 184.62 | 791.31 |

测量窗口组合 CPU p95=21.99%（100%=一核），组合 RSS峰=184.62MiB。比较域为应用+负载生成器/统计/采样器合计；当前都在同一Python进程，采样器同步读取ps/PG的开销计入延迟，不是应用独占资源。未来Java若独立进程，须把Java应用与等价Python负载/采样进程按时间对齐的CPU/RSS求和后比较；布局未对齐时仅观察不判通过。P1需交付等价adapter或另采独立server基线，且当前ASGI不含TCP。其他数据库活动会话的资源样本为 0/1731；PG资源是同一实例所有子进程的总值，RSS含共享页重复，不能作为本库独占内存。保留全部预热/测量轮次，没有剔除错误或异常尾部。

业务断言：no_unexpected_http_or_business_errors=True；all_accepted_tasks_answered=True；one_chat_charge_per_accepted_task=True；terminal_inputs_deleted=True；formal_submissions_equal_successes=True；one_mcp_submit_per_formal_success=True；no_grade_from_explanation=True；no_external_network_attempt=True。

队列边界：41 请求出现 20 活动/20 等待，20 成功、1 立即队满 429、20 等待 10s 后 429；这是预期背压，分母 41，不计作正常 mix 的异常率。终态临时输入为零，配额为 20，槽位归零。

恢复使用加速 10ms 外部脚本，SIGKILL 受理后和成绩提交后、答案终态前均能恢复，重放不重扣、不再调用 MCP，已完成成绩不重复。重驱动执行最大 0.074288s；该值排除了 30s grace、300s扫描间隔及进程启动。默认调度保守等待上界 330s 属源码推导，未实测实际最坏自动恢复。

MCP已进入而响应尚未返回时 SIGKILL，恢复先查 unknown 再以相同 job 再提交：跨进程实际 grade_plugin 调用 2 次，本地配额/成绩各 1。MCP提交调用数与本地幂等不能混为一谈，替身也不证明真实harness执行次数。这一安全边界为失败，未改冻结源码。running 同 job 再调用仅记录现状，不能证明真实 harness 会加入原执行。finished 能复用结果，不再提交。用户已接受COMP003（U08/P0-D13）：未来Java running只查/等，unknown/查询失败停止重投、system_error终态提示待核对、保留submitted账本、清理输入；Java尚未实现。

600s TaskService 外墙通过虚拟 loop.time 的 599/600.5 边界验证；真实 PG 落 timeout/system_error、清临时输入和释放槽，迟到 finish 被已有终态守卫拒绝。该用例不等同于等候真实 600s 墙钟。断订阅验证服务生成器关闭后后台继续、终态可重放；TCP/浏览器断线未验证。24h TTL 由功能 oracle 承担。

提出时历史候选见 `threshold-candidates.json`；当前以已冻结的权威 `migration/contracts/thresholds.json` 为准。每端点每轮绝对 p95 与允许差值同时满足，异常率 ≤0.1%，正确性硬限制为零越权/重复扣次/重复聊天成绩/永不终态；组合CPU/RSS与恢复指标严格按冻结适用域评估。Python正常mix通过，Java和生产容量尚未验证；真实模型、embedding与harness质量也未验证。

精确重跑命令、替身设置、统计方法、资源归属与限制见 `migration/oracle/performance/README.md`。原始耗时数组和日志仅留 `.migration-artifacts/p0/performance/`；可提交证据只含合成统计/断言。

最终只读PG核对：通过，全部7项断言通过；业务终态/扣次=10278，正式成绩/评测扣次=174，临时输入=0。合成模型usage费用unknown=20556条，显式零费用=0条，unknown未转换为0；不代表真实模型/harness费用。详见 `final-pg-audit.json`（绑定本次baseline-summary的SHA256）。
