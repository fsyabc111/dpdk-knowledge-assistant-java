# P0 Spec 最终证据复核

- 审查者：`/root/p0_review_spec`；日期：2026-10-24。
- 固定提交：`099dca2e2a1b44eb6b4319ca757822f41b9aa5b0`；差分：`git diff c94f43d...099dca2`。
- 沿用原 `review-spec.md` 和 `review-spec-recheck.md`，只写本文，不覆盖历史审查。未读取 Standards 最终审查结论。
- 本轮只读提交证据、已有运行 trace/CLI 破坏样例，并运行离线比较器/纯测试；没有连接 PG、导入 app、启动应用、重新采集 oracle/负载、真实上游调用、提交或推送。

## 结论

原 S1–S4 **全部关闭**，S1/S2 的动态条件已由本次固定提交的证据满足；未发现影响当前 P0 交付的新回归或新增必修阻塞。

P0 的 Spec 审查门禁通过，可交主 Agent 汇总阶段报告并申请用户阶段验收。该结论不等于用户已验收 P0，不放行 P1，也不表示 Java、真实上游或生产容量已通过。主 Agent 正在收口的阶段报告/资源恢复说明不作为本审查重复缺失项。

## 原问题最终关闭依据

| 原问题 | 复核证据与结果 | 结论 |
|---|---|---|
| S1 [P1] trace 只比行数/响应，遗漏真实数据库业务值 | p0-v2 已有25表完整业务初态、逐 case 实际 removed/added 与完整终态；186 HTTP + 1 auth + 11 PG/清理共198步可重建。两库原始 trace 再比较通过。扣次、成绩、job提交标志/关联、权限树、费用NULL/类型、引用rank/舍入分数、知识revision等真实CLI破坏样例均拒绝 | **关闭** |
| S2 [P2] 漏继承模型、管理列表只有空样例 | 静态模型64/64，ManagerAssignment自有2+继承10字段；黄金 `python-golden.json:16343` 对应实际teacher非空列表12字段，submission_count/student_count=1/1。`:16559` student实际80分→16.0。删除任一Manager新增字段的实际CLI候选均退出1 | **关闭** |
| S3 [P2] CRLF误放宽，冻结React无帧可读 | `compare.py:89-106` 保持LF空行/首event-data/EOF语义，`:97`拒绝CRLF/CR。最终9组纯测试中的CRLF负例和UTF-8逐字节分块正例再次通过；修复后未再改宽换行规则 | **关闭** |
| S4 [P2] error-only被强制要求done | `compare.py:109-140` 分开done/pending/error；合法error-only/start→error、结束后事件拒绝、截断done拒绝的最终纯测试再次通过。没有把HTTP 200或error结束改称成功done | **关闭** |

依据仍为 `acceptance.md:93/98-100/125/184` 的输入/前置/状态迁移/业务副作用判定，`foundation-slices.md:12/19` 的字段契约，以及冻结HTTP/SSE客户端语义。本次不是用静态66路由映射替代完整业务行为验收。

## 版本、来源与差分器复验

- 黄金 SHA256：`8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`。实际重算 `p0-v2/manifest.json:12-16` 四个文件摘要全部相符，涵盖黄金、路由映射、归一化审阅与业务投影。
- `auto/p0-v2-final-summary.json:4-23` 与两个数据库report一致：每端186观测、66路由、11 PG/清理case、29 schema表；25表业务投影；零pending、零临时输入、源码标记零命中。两个report数据库身份分别为 `p0_python_oracle` / `p0_candidate_oracle`，未把一库采样冒充双库。
- 离线加载已采集的两个原始trace再次 `compare(a,b)` 通过；直接验证黄金业务链通过。未依赖文字“通过”来替代实际重建。
- 只读重跑已留存的34份真实CLI候选：2项退出0、20项业务差分退出1、12项输入损坏退出2；均与提交报告相符，stderr为空，stdout均为可解析JSON。该集合包含非空Manager字段缺失、全部源码hash替换、跨类型task关联替换、raw排序、容差内/外、unknown→0、redact向量与容器/深度损坏。
- v2 verify再次退出0/186观测，历史v1 verify再次退出0/184观测。`git diff --name-only 08158c6 099dca2 -- migration/fixtures/p0-v1 backend frontend` 为空，确认v1修订2和冻结Python/前端未被本轮修复覆盖。
- `auto/account-disable-followup.json:6-10` 的四个真实PG断言均为true：新token有效、停用后同token无效、启用后旧token不复活、新token有效。此证据独立于历史v1已logout令牌样例，原补验缺口关闭；本审查没有重新操作数据库。

## 新回修没有放宽业务比较

- **文本投影**：`business_state.py:58-68/77-82/103-105` 只识别当前业务表实际登记的实体，将有序类型引用与剩余字面SHA256分开保留；不是删除所有疑似ID文本。最终纯测试验证已知实体替换会改变links，正文改变会改变指纹，未登记的ID字面例子仍完整进入指纹。原字节数保留。其目的是消除允许双射的生成实体在正文中的采样噪声，业务值、跨表关系和文本内容并未整体被忽略。
- **UUID端口**：`ports.py:73-95` 仅替换 grading_service 的UUID生成入口，冻结服务仍生成grade_16hex并运行原计分/事务。当前fixture的合法确定v4样本用于稳定随机数字串被原redact命中的差异，不证明随机熵。`manifest.json:7-10` 明示这个边界；原Python脱敏算法未改。
- **redact现状**：含连续数字的 `grade_abcdefab12345678` 实测为 `grade_abcdefab[号码已省略]`，无数字长串的 `grade_abcdefabaaaa4aab` 保持原值，两向量都进入可比较trace。破坏前者的CLI反例退出1；`known-differences.md:11` 登记默认保留源算法，不把它默默修成新产品行为。
- **hash与身份**：code_hash/code_sha256/input_hash仍在 `compare.py:184-187` 逐值校验，不参与实体双射。全部hash一致替换和跨实体类型全关联替换的CLI反例仍失败。
- **浮点容差**：U09/P0-D15已经接受raw检索分数绝对误差≤1e-12。manifest、business-projection、`thresholds.json:9-16` 与 `compare.py:212-220` 均只限定 `$.transaction_cases[*].ranked[*].score`。容差内通过、超出拒绝；节点顺序、引用rank/round4分数、业务得分维持exact。不是对所有浮点数统一放宽。
- **失败保留**：动态资格attempt01..05及早期指纹/redact/并列排序失败被 `review-fixes.md:66-73` 独立记录，最终通过引用attempt06。当前结论没有将资格失败计为通过。

## 三轮合成门槛复核

U09/P0-D15接受数值只冻结本轮P0合成门槛，权威为 `migration/contracts/thresholds.json`，不以历史 `threshold-candidates.json` 的待确认文字覆盖当前用户决定。

离线重算/核对结果：

| 项目 | 实际证据与判定 |
|---|---|
| 时长与轮数 | baseline-summary含3个120s预热、3个600s测量，测量排空时长另报；预热未被删除或混入测量样本分母 |
| 分组样本 | 测量登录180、作业读取21600、chat8419、正式提交144、讲解受理144、讲解终态144；aggregate与各轮n之和一致 |
| 每端点每轮 | 6组×3轮的p95均同时满足权威绝对上限和固定Python最差轮参考值+允许增量；每组每轮unexpected_errors=0。未仅以合并p95掩盖单轮 |
| 分位数 | n不足1000的分组p99保留null，未伪报精度；拒绝/异常保留分母。41请求独立队列中的1队满+20排队超时429单列为预期背压 |
| CPU/RSS | 1731个测量资源样本；组合CPU p95=21.9917839173%单核，RSS峰184.625MiB，满足100%/512MiB；各轮CPU p95约23.82%/22.07%/21.05%，也均达标。测量时其他oracle数据库活动样本为0 |
| 总账 | 7项最终PG审计均true；含预热的最终受理终态/扣次10278、正式成绩/评测扣次174、临时输入0。它们是全程总账，不与测量期chat8419/正式144混为一个分母 |
| unknown | 最终合成usage费用unknown=20556、显式零费用=0；未把没有真实单价改成0。MCP实际调用依外部边界账本，usage缺某类不证明真实费用为0 |
| 恢复 | accepted/已有finished结果的10ms脚本恢复执行最大0.074288042s，满足限定的1s；不把unknown/running自动重投称作Java安全策略通过 |

`baseline-summary.json` 实际摘要为 `b3c8200ae2feee65ee9e2f8d3f73cbea327baae05206758c7de7e5f8050261b2`，aggregate、final-pg-audit、delivery-verification均绑定同一摘要。性能runner当前SHA `7c151385d7b9ac879527c459b5c2bd283b12bb14ebc2141c389c7d61c3efa0f9` 与正式环境和delivery-verification记录相同，未用修后runner冒充正式测量代码。

适用域核对正确：CPU/RSS为应用+负载/统计/采样组合域，未来Java需同时间样本对齐相加，布局未对齐只观察；当前ASGI不含TCP/TLS/nginx。恢复1s只含进程已启动、PG健康、槽位可用的10ms脚本执行；330s是grace+扫描源码推导，未实测自动循环最坏RTO。局部RPO=0仅为应用SIGKILL且PG继续健康，未扩大到PG/主机故障或备份恢复。上述边界在权威阈值中明确，不构成未披露生产承诺。

## 未验证项及后续责任

以下均有后续卡/授权边界，不是本次P0仍缺失的原问题：

- J1-01：JDK/Maven/依赖实际构建、Java schema baseline及空库/完整合成0013/等价0015/重复启动路径、完整catalog和迁移失败负例。P0完成方案与owner，未冒充Java迁移已执行。
- J1/P1候选适配：Java协议与固定向量实现、CPU/RSS测量域对齐；当前仅Python双库自对照，尚无Java兼容/容量通过结论。
- J2/J3/J4及J5-01：完整认证模式×角色组合、其余28条完整并发/重试分支、React页面与全业务场景。现有66端点基础case映射不等于这些场景全部验收。
- J3：落实已批准COMP-003安全差异。Python unknown/running重投作为冻结源行为和失败证据保留；Java running只查/等、unknown/查询失败不重投、system_error+failure_code/保留账本/清输入均尚未实现。
- 另获授权live子批次：真实模型/embedding质量、人工审核题集、预算与允许差异、真实harness行为。替身和固定向量不能代替这些证据。
- J5/P5与切换授权：TCP/浏览器/nginx断线、自动恢复最坏时延、PG/主机故障/备份恢复、生产独占资源/停机/RPO、旧部署schema现场核实和迁入。原环境保持只读。

## 本轮实际执行与交还

工作目录均为仓库根，设置 `PYTHONDONTWRITEBYTECODE=1`：

1. 最终9组 `migration.oracle.test_review_fixes` 全部通过（退出0）；包含真实CLI临时文件子进程测试，没有app导入。
2. v2现有候选verify退出0/186观测；v1历史候选verify退出0/184观测。
3. 标准库/比较器只读脚本：四项manifest摘要、两个已采集原始trace比较、25表198步重建、实际Manager/学生响应检查；全部通过。
4. 对已存在的34份CLI破坏候选逐一只读重跑verify；2/20/12退出码分布及空stderr全部符合记录，未重新生成或覆盖样例。
5. 只读JSON/哈希统计：逐轮阈值、各资源曲线p95/RSS、样本分母、summary/runner绑定、最终audit、v1/backend/frontend无diff；全部通过。

本审查只新增本文，原报告不变；没有数据库/应用/外部副作用需要回退。主 Agent 可使用本结论关闭S1–S4并汇总P0阶段交付；用户阶段验收与下一阶段授权仍由用户决定。
