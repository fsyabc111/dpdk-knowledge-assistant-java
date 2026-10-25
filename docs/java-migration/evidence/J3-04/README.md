# J3-04 证据索引

当前为独立子切片证据，不代表 J3-04 门禁通过。

- 身份 oracle：Python `uuid.uuid5(uuid.NAMESPACE_URL, 'assignment-explain:grade_abc')` → `ca952def-7b8d-5e7f-b0fd-2504dcc6936d`。
- 纯函数：SubmissionExplainContractTest，12/12，零失败/错误/跳过；identity、purpose/双身份反例、context 字段顺序与缺省、九类 falsy results。
- 无源码长期保存：context 不含 source_text/code_hash；成绩事实来自 SubmissionRecordReader，源码哨兵未进入载荷。
- 真实数据面：ExplainDataIntegrationTest 3/3，截止后可讲解、四种统一404、确定性会话预建幂等、恢复可信成绩/当前标题、purpose/双身份/本人反例；动态库已删除。
- 未验证：HTTP权限、真实任务/扣次/重放、重启恢复任务、grade_plugin 零调用、成绩表不变、参考答案策略、断线/取消/停号。
- 原始日志：`.migration-artifacts/j3-04/unit.log`、`data.log`（gitignored）。
