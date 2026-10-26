# P5 metrics单case规格归因与验证结果

**结论：既有规格和P4独立审查支持按明确映射检查Java指标语义；没有逐字34-family兼容依据。本片不改变原strict FAIL，也不新增豁免或宣布全矩阵通过。**

## 既有依据与34项覆盖

- `foundation-slices.md:48,55` 要求日志/指标兼容映射写清，不假设自动指标同名。
- 主集成ab9c603树的P4 `review-spec.md:23`：持久领域gauge、过程unavailable，不能解释为完整源counter兼容；`review-standards.md:17`：明确过程缺项不伪称全覆盖，不据已明示不可用映射自动新增阻断。
- 冻结 `GET /metrics::success` source34分为Python runtime4、业务domain16（4持久gauge映射/12过程缺项）、业务created元数据4、自动HTTP10。逐行映射见metrics-mapping-families.md及acceptance/metrics/mapping.json。所有原family均列出，未仿造python_*或静态0凑数。
- Java现有HTTP method/status counter和dispatch summary只覆盖自身分派口径；未实现源HTTP bytes summary、histogram buckets或Python created/runtime指标。缺12个过程domain按available0明示；其他框架/元数据缺失在映射表说明，不伪称也都有marker。

## 实际执行

| 证据 | 实际覆盖 | 限制 |
|---|---|---|
| 纯validator 12/12、零skip | TYPE/gauge、白名单标签、重复/不安全/假0、已知值、unknown、缺项marker与34清单完整性拒绝反例 | 手工fixture不是live HTTP/PG |
| full04 observation30 raw | status200/textplain，Java6families/24series，12 unavailable0；冻结初始任务行+独立前30HTTP请求推导5known checks通过 | 原source34/java6 strict FAIL保留；本case无unknown正行 |
| rootUI51518只读HTTP（root声明生产5c8623c） | 10families/43series，TYPE/标签/12 marker检查通过；原始body hash已记 | 不修改或借用UI种子；known checks0，unknown正行未覆盖；不是最终源码 |
| 新owned TestPg+正式Application随机loopback HTTP | 四身份预检；UsageLedgerStore记录known21tokens/7成本、unknown NULL1行、cache_hit零调用/已知0；1终态任务；HTTP200，14families/30series，12known checks及unknown正行/NULL不补0/12缺项marker通过 | 非模型/MCP质量；只为指标局部场景，不证明完整教学链；context关闭/精确owned库finally删除，PG窗口已释放 |

原始wire/trace只在ignore路径保留，hash与来源在metrics-mapping-full-04.json、metrics-mapping-ui.json、metrics-mapping-seed-http.json。full04生产修复提交与workerHEAD由matrix提供，helper最终SHA当时pending；最终candidate冻结后还要重采。OperationsMetrics源码hash与主ab9c603相同，未改生产collector/adapter。

## root待裁决与未验证

本单case具备“按已接受的映射语义验收”的现有规格依据，且独立正/负/known/unknown证据已准备；不具备“Python34 TYPE逐字相等”结论。原strict比较若继续逐字要求34，差异仍真实存在。root需明确如何展示该已规定映射case与raw strict差异的并列结论；本worker不修改比较规则或添加豁免。

尚未验证：最终新源码/final helper重采绑定、完整源过程指标/框架自动指标等价（目前明确未实现）、生产可观测性/容量、真实模型/harness/live、外部nginx内网暴露演练（J5-03负责）。不得把semantic通过替换成全矩阵或这些事项通过。
