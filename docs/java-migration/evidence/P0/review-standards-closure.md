# P0 规范审查关闭记录

- reviewer：p0_review_standards；固定提交 `a207a4ad30597cb395744856667fd569f87fa882`。
- 本次范围：`git diff 099dca2...a207a4a` 中剩余 S-03 的 SSE done 状态类型校验；仅只读文件、只读 CLI 和纯内存语义检查。
- 结论：**原 S-01、S-02、S-03 全部关闭。此次限定复审范围内没有仍知的 P0 规范必修问题，没有发现该修复引入的终态语义回归。** 此结论不自动代表阶段用户验收或 P1 放行。

## 关闭依据

| 原问题 | 状态 | 依据 |
|---|---|---|
| S-01 身份格式被全局格式并集掩盖 | 关闭 | 沿用 c94f43d／099dca2 独立复审；字段类型校验和实际跨类型全关联反例已经通过，本次单行状态 guard 未改变身份规则。 |
| S-02 传递继承模型漏枚举 | 关闭 | 沿用已核验的 64 模型、ManagerAssignment 2 自有／10 继承／12 有效字段、owner/Java 去向及实际非空列表证据；本次未改枚举器。 |
| S-03 损坏输入无稳定退出码／JSON | 关闭 | 原顶层／日历／嵌套容器反例已在前次实际 CLI 复审通过；此次剩余 done 状态对象／数组边界及新增 4 种非字符串全部实测返回 2。 |

`migration/oracle/compare.py:132` 现在在任何终态集合判断前要求 `type(status) is str`，否则抛 `InvalidTrace('SSE done状态必须是string')`，由真实 `verify.main()` 返回稳定 JSON 与退出码 2。没有改写黄金，也没有通过通用异常捕获掩盖程序错误。

## 本次实际验证

所有命令在仓库根目录执行，解释器为 `.migration-artifacts/p0/venv/bin/python`，设置 `PYTHONDONTWRITEBYTECODE=1`。

| 验证 | 实际方法 | 结果 |
|---|---|---|
| 6 项新增真实 CLI | 只读 `.migration-artifacts/p0/v2-cli-regressions/sse-done-status-{object,array,null,bool,integer,float}.json`，逐项运行 `python -m migration.oracle.verify --fixture p0-v2 --candidate <path>` | 6/6 返回 2；全部输出 `invalid_manifest_or_input`，detail 为 `SSE done状态必须是string`；stderr 全空，无 traceback |
| 正常 v2 | 同一 CLI，候选为 `.migration-artifacts/p0/candidate-trace-p0-v2.json` | 返回 0，186 观测通过，stderr 为空 |
| 正常终态语义 | 直接调用 `validate_sse`：answered 不带 terminal、answered/terminal:true、pending/terminal:false、合法 error | 4/4 分别判为 done／done／pending／error |
| 错误终态语义 | pending/terminal:true、未知状态字符串、answered/terminal:false、done 后追加事件 | 4/4 抛 `Difference`，没有误放行 |
| 黄金和 manifest | SHA256 与 `git diff 099dca2...a207a4a -- <golden> <manifest>` | 黄金和 manifest 无差异，manifest 的 4 项文件摘要全部通过；黄金 SHA256 仍为 `8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14` |

本次没有运行会写入原证据／候选的 `verify_v2_regressions.main()`。主 Agent 的 40 项报告已读取；本 reviewer 实际重放为上述 6 新项和 1 正常项，前 34 项已在 099dca2 独立重放，不冒称此次再跑全部 40 项。

辅助校验曾将整个 fixture 目录设为“无差异”，因 `p0-v2/README.md` 的正常文档更新而断言失败；随后改为准确核对黄金、manifest 及其 4 项摘要，全部通过。此为审查辅助断言范围过宽，不是实现或黄金回归。

## 边界与交还

- 本次未导入 app/fastapi、连接 PG、启动应用、运行动态 oracle／负载或调用外部上游；PG 已停止的现场状态由主 Agent 管理，本 reviewer 未重新探测或操作。
- U08 安全差异、U09 数字门槛和最新动态证据沿用已接受记录；不重复报告旧阶段草稿中的历史待办。真实模型／embedding／harness质量、完整业务分支和 Java 实现仍按各卡边界，不由本报告扩展验证结论。
- 原规范审查报告、两份复审及 final 报告均保持历史原样。本 reviewer 唯一写入本文，没有修改实现／状态、提交或推送；无进程和资源需清理。
- 可交回主 Agent 汇总阶段报告与用户验收；**P1 仍未授权**。
