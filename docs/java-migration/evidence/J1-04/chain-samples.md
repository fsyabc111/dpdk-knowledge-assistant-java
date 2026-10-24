# J1-04 最小问答纵向切片证据

日期：2026-10-25。分支 `codex/p1-p2-longrun`。

## 纵向可行性判定：已验证（合成域）

请求 → 固定版本检索（真实 Java SQL 检索 + 合成 PG 冻结 fixture）→ 模型（固定响应替身）→ 结构化答案/引用/裁决 全链路在 `MinimalQaChainIntegrationTest` 打通；同链路的纯单元测试（`MinimalQaSliceTest`）锁定裁决顺序与引用绑定语义。**不声明**：完整 /api/chat 产品契约、持久会话/任务、生产 SSE、真实模型质量（均属 J3/P5 或 live 授权批次）。

## 四联样本（golden 用例 q2-filename-ascii）

| 环节 | 值 | 来源 |
|---|---|---|
| 固定输入 | release=rel-alpha；query="recv loop 分表入口"；1024 维固定向量 | J1-03 冻结 golden-retrieval.json（生成链可复现，非运行推导） |
| 检索中间值 | 四腿召回→RRF 融合→重排→MMR；期望顺序 li_a3,li_a1,li_a2,li_a4,li_a6,li_a7 | 同上（J1-03 集成测试已按腿/分数容差 1e-12 对照） |
| 模型响应 | "[1][6] 依据见证据片段。"（固定替身，POC 只证明链路不证明模型质量） | 本切片测试内固定 |
| 最终引用 | answered；6 条 citation，rank=1..6，chunk_id=期望顺序，score=Python round4(golden selected.score) | 断言于 MinimalQaChainIntegrationTest.q2 |

## 裁决顺序语义（与 Python 源逐行对齐）

- precheck：`empty_answer` → `invalid_citation`（ref<1 或 >len(chunks)）；**先于** postcheck。
- postcheck（无 tools）：`no_evidence`（chunks 空）→ `missing_citation`（有 chunks 无 refs）。
- 反例锁定：零证据 + 模型仍给编号 → `invalid_citation`（不是 no_evidence）——q7 双向断言。
- 检索异常 / 模型异常 → 受控 `system_error`，不向调用方抛栈。

## 引用绑定

- 全部片段保留、顺序=证据编号、同文件不合并（六引用黄金用例锁定的行为）。
- score = Python `round(x,4)`：BigDecimal(double 精确二进制值) + HALF_EVEN；非 HALF_UP。
- engine 由 `li_` 前缀判定。

## 验证命令

- 全量：`./backend-java/mvnw -f backend-java/pom.xml -B verify`（带 DPDK_TEST_PG_* 环境）→ `Tests run: 150, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 本卡：`-Dtest='MinimalQaSliceTest,MinimalQaChainIntegrationTest'` → 12/12。

## 未验证项

- 正式 /api/chat 路由、SSE、会话/任务、真实模型、页面联调（按卡属 J3/P5/live 批次）。
- POC 装配为测试入口（隔离 profile 语义由 J3-03 接管时落实 `chat/poc` 退出机制）。
