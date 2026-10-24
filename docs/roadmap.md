# 统一路线与首批执行单

更新日期：2026-09-23。产品处于个人开发验证阶段，目标为可靠的 DPDK 教学助教闭环。
本文是唯一阶段状态入口；当前事实见[架构](architecture_current.md)，目标约束见[决策](decisions.md)。旧 P/M/B/E/K 编号只用于追溯，不构成并行执行队列。

## 1. 当前状态与总路线

截至 2026-09-23，S0–S4（含 S4-d 补）均已按各批记录交付并获用户验收；总审查修复四批也已由用户验收（§14）。**阶段验收不等于所有候选场景均已验证**：live mark、harness 重启中的在途作业、多学生班级隔离浏览器走查和 CSV 导出端到端仍按 §13/架构文档保留未验证。下表保留阶段目标与当前状态，具体证据等级以各批记录为准。

| 阶段 | 交付目标 | 依赖与边界 | 放行依据 | 当前状态 |
| --- | --- | --- | --- | --- |
| S0 可信基线 | 核对现状、复现问题、纠正完成声明的适用范围 | 保留现有行为，只调查；首批执行单见第 4 节 | 逐项证据、影响、未验证项与后续归属可查 | S0-01 已执行并验收（见第 7 节与[基线报告](baseline_review_20260918.md)） |
| S1 教学闭环修复 | 页面成绩契约、引用身份、评分保留、工具配对、源码身份及教学反馈正确 | 先确认反例；每个修复批次独立验收，不夹带框架迁移 | 对应反例变绿且完整场景无回退；正式计分与聊天练习隔离 | S1-a/b/c 均已执行并验收（见 §8–§10） |
| S2 执行边界收敛 | 共用聊天生命周期及评测业务服务，证据身份统一，judge 转离线验收用途 | 以 S1 可核查行为为基线；保留必要的登记、幂等、反馈和用量；不同时删除数据库支持 | 正常/失败/取消/重放路径契约一致，API/SSE 兼容，实际用量可归因 | 已执行并验收（见 §11）；judge 离线化在 S3-e 落地 |
| S3 恢复与结果讲解 | 持久化任务、源码临时保管、跨服务步骤级恢复、正式结果按需讲解 | 先冻结第 3 节相关决策；涉及后端与独立 harness，再接前端恢复；不重复扣配额与记成绩 | 重连/重启/响应丢失/重复请求/取消/输入过期故障注入，源码清理和权限验证 | **全部交付并验收**（§12）：a 决策冻结 / b 源码带外与去重 / c 任务持久化恢复 / d 结果讲解 / e 离线 judge+前端重连 |
| S4 PG 收敛与试用验收 | 退出 SQLite 和无用旧入口，形成可试用版本 | PG 测试建设从 S1/S2 提前，恢复在 PG 上验收；删除晚于替代覆盖和数据核查 | 新库/升级/事务/历史引用/迁移与备份恢复通过，完整教学场景与容量验收 | **S4-a–d 及 S4-d 补均已交付并验收**（§13）；遗留未验证项如实保留，不扩成新的自动批次 |
| K 教学资料专项 | 经审核的中文资料改善理解与排错 | 相关问答/引用/索引链路与独立基线稳定后可单独授权；不必等待 S4 或新 reranker | 小规模语料对照有可复核收益，源码定位不回退，资料可维护与撤回 | 暂缓（2026-09-21 用户决定）；已有三批草稿，未发布 |

S1–S4 是阶段而非一次大提交。每阶段拆批后逐批授权；独立小修复可以调整先后，但必须说明依赖变化，不能自动跨批。恢复是明确目标，不因本轮仅做基线而取消。

## 2. 旧规划迁移对照

来源均在 [2026-09-18 归档](archive/2026-09-18/README.md)，历史证据保留原文。

| 旧项目 | 去向 | 当前处置 |
| --- | --- | --- |
| Agent M1–M3：五工具、教学反馈、账号、成绩、隔离 | S0/S1/S2 | 保留能力与边界；纠正旧完成声明，补实际闭环验证 |
| M4a / P0.1：上下文、预算、画像、MCP 重试、注入防护 | S0/S1/S2 | 保留护栏与计量，复现 F1–F6、校准预算；不把历史全绿当当前通过 |
| M4b：源码带外、checkpoint、interrupt/resume | S2/S3 | 改写为业务任务与外部执行协作；旧 session_id=thread_id 和固定 interrupt 位置不直接定案 |
| M4c：黄金集扩充、live、模型 judge | 各修复批次及 S4 | 风险驱动补覆盖；judge 离线优先，题数不代替质量结论 |
| RAG A/C/E：检索基线、离线 fixture、幂等 ingest、答案门禁 | S0/S1/S4 | 保留工具与可复现口径；先小样本人工校准，再决定扩大规模 |
| RAG B1–B3：LI 单数据面、FTS、权重实验 | 当前底座及条件性备选 | 保留实现和历史实验，不重做已完成实验，不盲抄其他环境参数 |
| RAG B4、HNSW、zhparser 再实验、缓存/画像扩展 | 备选清单 | 仅由真实召回/排序/容量问题触发，单变量对照 |
| P0 本地环境 | 本地开发手册及 S0 | 保留环境说明，执行前复核；安装历史不等于当前运行状态 |
| P1 全链路用量、价格和流式 usage | S0/S2/S4 | 核查遗漏与失败路径；未定价与 unknown 如实保留，换上游再校准 |
| P2.1：先限制 judge 输入再重跑 15 题 | S0/S2 | 旧执行顺序停止；保留失败证据，围绕新离线 judge 目标重新设计评估 |
| P2.2：100 题、盲审与评分 | S1/S4 | 保留候选题库、引用核对、事实审核；100 是候选规模，不硬性充数 |
| P3：20 路 30 分钟、上游故障、灰度 | S3/S4 | 保留故障与容量验收；聊天/评测分开建模，冻结目标负载后再跑 |
| RAG D / P4：PG-only 与 legacy 清理 | S1/S2 提前建设测试，S4 删除 | 保留迁移、旧引用与回退检查；仍用的 reader/chunker/排序能力先迁出或保留 |
| 产品化登记、幂等、问题工作台、共享知识 | S1/S2 | 保留已有有用能力，整改重复收尾；暂缓扩张管理功能 |
| K / P5：教学资料 | K 专项 | 保留 1–2 篇校准、5–10 篇试点、来源审核、语料对照与生命周期；不建第二数据面 |
| dev_notes：首次 CI 验证、覆盖率、pg_restore、多轮压测 | S0/S4 | 复核 CI 实际运行；覆盖重要路径优先于数字增长，恢复与多轮负载单独验收 |
| 旧交接中的“未提交”“下一批”命令与成本估值 | 归档 | 只用于理解历史；不复制成当前授权、预算或完成状态 |

## 3. 批次管控与验收规则

每批开始时固定：用户可见目标、允许改动范围、非目标、兼容要求、验收场景和失败/回退方式。实现者可自主完成已授权范围内的实现与验证；发现超范围问题先登记，不顺手扩大改造。

各批记录以下状态，不能用一个“完成”合并：

| 状态栏 | 填写要求 |
| --- | --- |
| 实现/产物完成 | 代码或调查产物已存在，注明提交；不适用的项说明原因 |
| 自动验证 | 具体命令、环境、结果、证据位置；未运行写未验证 |
| 场景验收 | 用户流程与失败场景是否满足标准，不能用局部测试代替 |
| 用户验收 | 待验收/通过/需修改；只记录用户实际结论 |
| 下一批授权 | 独立记录，验收通过不自动表示允许继续 |

- 报告绑定代码、harness、题库、模型、索引 release 和测试口径；禁止在报告里复制凭据。估算费用与账本实测分列。
- 修复断言可以纠正错误测试，但须解释旧断言错误及新契约；禁止通过放宽标准掩盖真实失败。
- 真实模型、运行中数据库、迁移、部署和压测分别列执行范围与预算/环境，不把命令示例当授权。
- 每批提交独立证据记录，在本文件登记状态和链接。归档不继续追加当前进度。
- S3 前明确恢复权限、源码过期、执行所有权、取消、任务身份与容量等[未决事项](decisions.md#3-必须在相关实现批次前决定的事项)。本批不假定技术选型或 API/schema 已冻结。

## 4. 首批执行单：S0-01 可信基线核查

### 目标与非目标

交付一份能指导修复的基线报告与问题清单。仅调查和隔离复现，不修改业务实现、原有测试断言、依赖、配置、索引和活动数据；不自动开启下一批。

### 输入与环境约束

1. 阅读当前架构、决策、本文件及原有 [P0.1 独立报告](p01_independent_acceptance_20260918.md)。该报告是输入证据，不直接当本批复验结论。
2. 记录助手与 harness 的 HEAD、分支、工作区状态；不覆盖用户现有变更。报告基线含时间、工具版本和脱敏后的行为配置，禁止输出整份 `.env`、连接凭据或学生源码。
3. 只使用临时 SQLite/独立临时 PG 测试环境、合成学生代码和网络替身。先审查测试 fixture 的数据库清理及网络行为；本批不对现有开发/生产库运行测试，不通过启动脚本顺带迁移或 ingest。
4. 真实模型与运行中环境验收作为待执行列表，写清范围、前提和预算，另行确认后执行。

### 核查顺序与场景

| 任务 | 方法与应交付证据 |
| --- | --- |
| 前后端契约 | 比对学生汇总/教师协议列的 API 响应与页面读取；使用合成响应复现显示错误，区分静态确认与界面实测 |
| 引用身份 | 同文件多片段、跨轮检索与四条上限反例；核对模型编号、响应列表、落库引用和读取来源 |
| 已完成评测保留 | 模拟 MCP 成功后模型失败；分别覆盖零输出、已有前置文字、JSON/SSE、落库与展示 |
| 工具协议与预算 | 轮数/token 达限转 finalize 时检查 call/result 配对；最近已完成评测携带长源码，复核地板超限反例 |
| 源码与重复执行 | 用合成原件对比模型参数、harness 输入和记录 hash；同请求重复 grade_plugin；上传、裸粘贴及不同围栏的持久化/回显 |
| 恢复与身份现状 | 静态梳理重放、取消、响应丢失、请求幂等与附件身份；检查跨请求 release 固定范围，不将现状描述成恢复能力 |
| 历史声明 | 逐项对照独立报告 F1–F7 和架构 C01–C10；核对 P0.1 口径、P1 用量、P2 无答案、CI/PG 验证的适用版本 |

### 产物与完成条件

产物为 `docs/baseline_review_20260918.md`（文件名标识本轮计划，正文记实际执行日期），原始日志/复现脚本放独立的 `.logs/baseline_review_<实际时间>/`，不覆盖旧证据。临时日志不冒充已随 Git 交付；报告说明取得或重建证据的方法。

每项必须列出：编号、现象、影响路径、代码依据、复现步骤与输入、预期/实际、环境、证据位置、确认/未复现/未验证状态、建议修复批次。纯阅读无法证明的结论明确保留。

完成条件：所有清单项有可核查处置；关键未知项与外部验证前提明确；给出最小修复顺序；用户可依据报告决定下一批。**不要求本批修好全部缺陷，也不以全量测试通过替代调查结论。**

## 5. 本次文档批次记录

| 项目 | 状态 |
| --- | --- |
| 范围 | 九份旧文档归档、四份当前文档、入口及专项/手册引用整理 |
| 实现/产物 | 已编写；不含业务修复 |
| 自动验证 | 通过：23 份 Markdown 的本地链接与锚点检查；九份归档正文仅相对链接变化；原独立报告 SHA-256 一致；十个非 Markdown 文件仅文档路径替换，Python AST 归一化后相同；`git diff --check` 通过 |
| 场景验收 | 已核对文档阅读入口和第 2 节任务迁移对照；业务场景未运行，不能据此标记 S0–S4 通过 |
| 用户验收 | 通过（用户授权 S0-01 推进） |
| S0-01 授权 | 待用户单独授权，不随本文自动执行 |

本批验证证据在本机 `.logs/docs_cleanup_20260918/`，不随 Git 提交；目录含检查脚本、归档前正文快照与检查输出。校验方法为原文重定位链接后比较、原报告哈希比对、代码引用替换及 AST 比对、本地 Markdown 目标与标题锚点检查。共享交付以本表和 Git 差异为入口，复核时从归档前提交取原文重做比对。未运行全量业务测试、迁移、索引或真实模型调用。


## 6. 2026-09-21 进度核对与资料专项暂缓

用户决定先搁置中文资料库建设。K 专项暂停新增资料、后续审核推进和入库实验，已有草稿与证据包保留；恢复时另行授权。既有知识源和索引不因本次暂缓而删除或变更，S0–S4 主线不依赖 K 完成。

本次核对基于助手 HEAD `7227c5c2d707819de86aaba77948069c9c33189a` 与仓库文档，核对前工作区干净：

- 9 月 18 日文档收口后，最近一次提交只新增 `knowledge_work/` 资料产物，没有升级主线业务代码变更。
- K 已有三批共 7 篇草稿：[首批 2 篇](../knowledge_work/zh_pilot/delivery.md)、[第二批 4 篇](../knowledge_work/zh_batch02/delivery.md)、[第三批 TLS 1 篇](../knowledge_work/zh_batch03/delivery.md)。均待人工审核；交付记录明确未发布到知识索引，当前知识源清单不包含 `knowledge_work/`。提交标题中的“入库”指 Git 收录，不能作为索引生效证据。
- 资料的静态检查与独立审核记录属于历史证据，本次未重跑；没有独立语料 A/B 收益验收。
- S0-01 报告 `docs/baseline_review_20260918.md` 尚不存在，未发现新门禁下的主线交付记录；S0–S4 状态保持不变。（本条描述的是本节核对当时的状态；S0-01 已于同日稍后执行，见第 7 节。）
- 本次仅同步进度与暂缓决定，`git diff --check` 通过；未运行业务测试、数据库、索引、真实模型或部署验证，不构成 S0 执行或下一批授权。


## 7. S0-01 可信基线核查批次记录

执行日期 2026-09-21（文件名沿用计划命名 `baseline_review_20260918.md`）。报告：[S0-01 可信基线核查报告](baseline_review_20260918.md)。

基线：助手 HEAD `7227c5c2d707819de86aaba77948069c9c33189a`（分支 main，工作区含三份既有未提交文档改动，本批未动）；独立 harness HEAD `9ed5cfd7f03cd8b9c062a8d1a8d621403aaadcbc`（分支 master，工作区干净）。

**范围收窄**（用户 2026-09-21 确认）：比对 `7048d8c..7227c5c` 证实两者之间业务代码只有十处注释内文档路径字符串替换、无任何逻辑变化，故 P0.1 报告的 F1–F6 按版本继承，不重跑；本批新写隔离复现只覆盖成绩契约与恢复/请求身份。执行单的七个核查项全部有处置，证据等级按项区分。

| 项目 | 状态 |
| --- | --- |
| 范围 | 七个核查项的调查与处置；新复现 C01 成绩契约、C10 幂等与附件身份、C07 恢复/取消/release 范围 |
| 实现/产物 | 基线报告 + 六个证据文件（脚本、两份原始日志、结构化结果、合成响应）。未改业务代码、既有测试、依赖与配置 |
| 自动验证 | 自建隔离脚本执行两次（首次 9 确认 / 1 未复现，修正幂等键格式后重跑 10 项全确认）。环境为 `backend/.venv` Python 3.11.15 + 临时 SQLite + 全部上游替身。**未运行仓库自带测试集**（fixture 隔离缺口见报告 B0），未跑 ruff、前端测试、迁移、索引或真实模型 |
| 场景验收 | 未做。未起浏览器与后端服务，未走 account 模式端到端；B1 为取值层确认，不是界面实测 |
| 用户验收 | 通过（用户授权 S1-a 推进） |
| 下一批授权 | 未授权 |

核查结论共 14 项编号（B0–B14）。新确认且原文档未记或记轻的有：成绩契约实为六处错位而非两处（含协议列整列消失）、请求幂等键把不同源码折叠为同一身份并重放旧答案、附件完全不参与幂等键、索引 release 的固定范围只在单请求内（`llama_index_service.py:412` 的 docstring 与实现不符）、测试 fixture 未中和数据库与上游地址。最小修复顺序与依赖理由见报告第 5 节；未验证项与外部验证前提（含真实模型、真实 PG、浏览器实测）见第 4 节，均需另行授权。

证据在本机 `.logs/baseline_review_20260921_1530/`，不随 Git 提交；报告第 6 节给出不依赖本机日志的重建命令与隔离机制说明。


## 8. S1-a 批次记录：B1 成绩契约修复

执行日期 2026-09-21。目标为[基线报告](baseline_review_20260918.md) B1 的六处前端取值错位，并以 account 模式端到端走查作为验收。非目标：B1 以外的任何修复。

修法：把六处派生抽成纯函数 `frontend/src/grades.js`（前端无 jsdom / testing-library，加依赖属于夹带；抽纯函数才能用现有 vitest 锁住取值与后端契约一致——B1 正是这类长期未被发现的静默错位）。`main.jsx` 六处改为调用它们，`client.js` 的错误契约注释一并更正。后端未改一行：错的一直只有前端取值。

| 项目 | 状态 |
| --- | --- |
| 范围 | B1 六处取值修正 + `grades.js`/`grades.test.js` + `client.js` 注释 + `architecture_current.md` C01 更正 + 基线报告 B1/§5 补 account 限定 |
| 实现/产物 | 前端 2 新文件 2 改文件，文档 2 改文件。未动后端代码、依赖、配置、`.env` |
| 自动验证 | `npm test` 75 passed（新增 12 例：契约 / 宽松降级 / 回归绊线）；`npm run build` 通过。未跑后端 pytest（B0 隔离缺口未修，本机跑默认集会打到真实开发库） |
| 场景验收 | **已做**。account 模式端到端：leader→admin→viewer 三级账号、真实 harness 容器评测、两次提交（89 分 8/9 → 100 分 9/9，最高分保留）。教师成绩表协议列/班级/得分/总分、学生卡片、学生详情表、CSV 导出逐格与接口 JSON 对照一致。数据库为临时 SQLite，未碰 `assistant_dev`，未调真实模型 |
| 用户验收 | 通过（用户授权 S1-b 推进） |
| 下一批授权 | 未授权 |

走查证据在本机 `.logs/s1a_walkthrough_20260921_1849/`（三张页面截图、三份接口 JSON、导出 CSV、`walkthrough.md`、可复跑的 CDP 截图脚本、临时库与两份合成提交源码），不随 Git 提交。

### 本批登记的新发现（均未修，不属本批范围）

| 编号 | 发现 | 归属建议 |
| --- | --- | --- |
| W1 | `DATABASE_BACKEND=sqlite` + 默认 `RAG_ENGINE=llama_index` 时后端**起不来**：`main.py:45-50` 启动钩子无条件预热 PGVectorStore，而 `llama_index_service.py:397-401` 的 `_connection_string()` 对 SQLite URL 原样返回，驱动不匹配即抛错。AGENTS.md 称 SQLite 是「显式本地回退」，但该回退在默认引擎下不可用，必须另设 `RAG_ENGINE=legacy` | S4（PG-only 收敛时一并定性：是修回退还是正式取消 SQLite 回退）；在此之前所有 SQLite 环境都要显式设 legacy 引擎 |
| W2 | 初始密码为学号且 `must_change_password: true`，但前端只在管理页显示「待改密」标记（`main.jsx:529`），登录后无强制改密流程 | 需先定性是强制契约还是温和提示；若为前者则属 S1 |
| W3 | 未登录状态下前端即对受保护端点发无令牌请求，后端每约 30 秒记一条 `auth_failed`。功能无碍，属日志噪音 | 低优先，随手批次 |
| W4 | 学生详情表中未提交的协议显示「最高分 `—`、折算得分 `0`」（API `earned=0.0` 非 null），两列风格不齐 | 观感问题，需产品口径决定后再改 |
| W5 | 本机 `dpdk-assistant-harness:local` 镜像早于 Dockerfile 的 `TH_MCP_*` ENV，裸 `docker run` 会静默退出（compose 显式传 env 不受影响） | 环境事项，非代码缺陷；重建镜像即可 |

W1 是本批最值得注意的一条：它说明「SQLite 本地回退」这条在文档里被当作现成能力的路径，实际在默认配置下不可用。


## 9. S1-b 批次记录：B0 测试 fixture 隔离

执行日期 2026-09-21。目标为[基线报告](baseline_review_20260918.md) B0：把数据库与上游地址的中和从「`.env` 存在即豁免」改为 autouse fixture 的显式契约。非目标：B0 以外的任何修复；不改业务代码、既有断言、CI。

修法：`_isolate_env_and_caches` 对无 `live`/`postgres` mark 的用例强制 `DATABASE_BACKEND=sqlite` + `DATABASE_URL=sqlite:///{tmp_path}/test.db`（每用例独立文件），LLM/embedding/harness MCP 一律指向 `127.0.0.1:9` 死地址 + dummy key；带 `live`/`postgres` mark 的用例不中和——这是旧豁免分支的真实意图（它们才需要 .env 的真实配置），改为按 mark 显式表达。新增 `tests/test_conftest_isolation.py` 三条金丝雀断言，把中和契约变成可自检的测试。业务代码未改一行。

步骤①只读盘点结论：默认集内所有真实触库用例均自供给（`sqlite_db` / 直接 `SQLiteProvider` / stub `get_conn`）；25 个不设 `DATABASE_*` 的文件中唯一吃 ambient env 的是 `test_provider_lifecycle.py`（懒构造 provider 不连库，中和后取 SQLiteProvider，断言仍成立）；`test_postgres_runtime.py:35` 读 ambient `settings.database_backend`，是 mark 豁免必须存在的用例；无模块级 `get_settings()`。

| 项目 | 状态 |
| --- | --- |
| 范围 | `tests/conftest.py` + `tests/test_conftest_isolation.py`（新）+ `AGENTS.md` 测试约定一句 + 基线报告 B0 状态行 + 本文本节 |
| 实现/产物 | 已编写；未动业务代码、既有测试断言、依赖、`.env` |
| 自动验证 | 本机 `.env` 在位且**不导出任何覆盖变量**：默认集 `681 passed / 96 deselected`（原 678 + 3 金丝雀），金丝雀 3 passed，`ruff check .` 通过。`postgres` mark 对专用测试库 `assistant_rag_test`（ambient env 显式指向它）：`5 passed / 1 skipped(zhparser 缺扩展) / 3 failed`——`test_postgres_runtime` 三例需预 ingest 的 `knowledge_chunks` 数据，空库前置不满足，**非本批回归**（其 ingest 需真实 embedding，属 §4 V5 授权范畴） |
| 场景验收 | 不适用页面走查；本批验收口径即「`.env` 在位时默认集可安全裸跑且不触真实库/上游」，已由无导出变量的全绿运行 + 金丝雀断言证明 |
| 用户验收 | 通过（用户授权 S1-c 推进） |
| 下一批授权 | 未授权 |

证据在本机 `.logs/s1b_fixture_isolation_20260921/`（notes.md 含全部命令与结果解读、canary/默认集/postgres/ruff 四份日志），不随 Git 提交。

### 本批登记的新发现（未修，不属本批范围）

| 编号 | 发现 | 归属建议 |
| --- | --- | --- |
| W6 | `pytest -m postgres` 不带显式环境变量时，`test_postgres_runtime.py:35` 会吃 `.env` 的 ambient `DATABASE_URL`（本机 = `assistant_dev`）——它与其余三个 postgres 文件不同，后者强制要求 `RAG_TEST_DATABASE_URL`；且该文件还需预 ingest 的 `knowledge_chunks` 才能全绿 | 低优先：把该文件对齐到显式测试库约定并注明 ingest 前置，可并入 S4 PG 收敛 |


## 10. S1-c 批次记录：B5+B4+B3 agent 降级路径合批

执行日期 2026-09-21。目标为[基线报告](baseline_review_20260918.md) B5+B4+B3 合批（同在 agent 降级/finalize 路径，改任一处都要重跑同一套黄金集）。非目标：其它缺陷、SSE/JSON 契约形状、提示词与工具实现。

拆块与顺序（子 agent 实施，lead 定口径、审 diff、统一收尾）：**A/B5（`context.py`）先单独落地并审过**——预算收敛修好后 B/C 的触发形态才稳定；其后 B/B4（`graph.py`）、C/B3（`service.py`）真并行。`tests/agent/test_golden_scenarios.py` 与 `golden_support.py` 全程归 lead，三块均未触碰。

修法定案：

- **B5**：第 3 级参数压缩判据从「是不是最后一个单元」改为 per-call「id 有配对 `ToolMessage` = 已执行可压」；未执行的悬空调用参数原文保留。第 5 级保护集从「最后单元」改为「最后一个**已执行**工具轮所在单元 + 最后一条 human 所在单元」——悬空未执行单元可整单元丢弃，已执行证据轮保留。
- **B4**：`finalize` 进模型前经 `_pair_unexecuted_tool_calls` 给末尾悬空调用补 `{"ok": false, "error": "not_executed"}` 合成 `ToolMessage`（与 `run_tools` 结果同构，含 `tool_call_id`/`name`）；只改发给模型的消息副本，`state["messages"]` 不动；新增 `agent_tool_calls_not_executed` 结构化日志。
- **B3**：流式异常分支 `context.grading_results` 非空即无条件 `build_fallback_answer` 追加 `delta`（有无前置正文都补）；`error` 事件仍只在已有部分正文时发——四形态序列：空输出 → `delta(fallback)+done`；部分正文有评测 → `…+delta(fallback)+error+done`（新）；部分正文无评测 → `…+error+done`（不变）。

| 项目 | 状态 |
| --- | --- |
| 范围 | `agent/context.py`、`graph.py`、`service.py` + 对应三个测试文件（新增 8 例）；文档三处。业务/契约/提示词/工具/黄金集文件未动 |
| 实现/产物 | 已编写；子 agent 三块文件级互斥，A→B/C 串行依赖由 lead 控制 |
| 自动验证 | 触及套件 + 黄金集 `86 passed`（30+14+6+36）；全量默认集 `689 passed / 96 deselected`（681+8 新增）；`ruff check .` 通过；前端 vitest `75 passed`（未改动）。环境：本机 `.env` 在位、**无导出变量裸跑**（S1-b 后安全）。A 块另做反向有效性验证：换回 HEAD 版 `context.py` 时 prep11/prep11c 如期 `floor_exceeded` 失败 |
| 场景验收 | **未做**。未起浏览器与真实模型；B3 触发前提（带文字的 tool_call 消息）在 S0-01 已复现，本批以确定性替身验证修复 |
| 用户验收 | 已通过：用户在 2026-09-22 移交 S2，并在本批再次明确确认「S1-c 验收通过」 |
| 下一批授权 | 用户已单独授权 S2；范围见 §11，不含 S2/S3 源码治理或 S3 恢复 |

证据在本机 `.logs/s1c_agent_fallback_20260921/`（notes.md + 四份日志），不随 Git 提交。

### 本批登记的新发现（均未修，不属本批范围）

| 编号 | 发现 | 归属建议 |
| --- | --- | --- |
| W7 | `route_after_tools → finalize` 边在当前图形状下不可达：`tokens_used` 只在 planner 节点更新，预算超限必然先在 planner 路由被拦截；tools 出口的检查是防御性死代码 | 低优先；S2 图收敛时顺手清理或注明 |
| W8 | B4 的合成 `ToolMessage` 使末尾调用计入 `_answered_call_ids` → `_last_answered_tool_index` 指向合成单元，最后一个**真实**工具轮在 trim 第 1 级被当作早轮压缩（修复前因末尾悬空它反而算最近轮保留原文）。合成单元 ~95 字符不占预算，现有预算测试全绿 | 如实登记；若未来觉得「真实证据被摘要」影响 finalize 质量，可在 `_pair_unexecuted_tool_calls` 标记合成结果让 answered 判据跳过 |
| W9 | `service.py:418` 的 `tool_call_chunks` 守卫存在合并块盲区：模型把「正文 + tool_call」合在同一 chunk 时整段正文被 `continue` 丢弃（非流式聚合路径可触发；真实 OpenAI 流式分块到达，不触发） | 潜在边角；S2 或观察后再定 |
| W10 | `_drop_units` 的偏移删除（`start - offset`）依赖「各级变换保持条数/顺序不变」的隐含约定；当前成立，未来若某级改变条数会静默错位 | 登记；context.py 下次改动时考虑加断言固化约定 |


## 11. S2 批次记录：请求身份分层 + 引用同源 + 会话锁 release

执行日期 2026-09-22。目标为[基线报告](baseline_review_20260918.md) B9+B10（请求身份）、B2（引用口径）、B14（release 固定范围 + `llama_index_service.py` docstring），全部输入为 decisions.md §6/§7 已冻结决策。非目标：B6/B7/B8 源码治理、B11–B13 恢复项、W1–W10 登记项——均未触碰。

实现拆块（子 agent 实施，文件级互斥；`rag_product_service.py` 被身份与引用两块共用 → A→B 串行，C 独立并行）：**A** = B9+B10 身份（`rag_issue_service.py`、`rag_product_service.py`、alembic 0012、schema 三处同步、schemas）；**B** = B2 引用统一（`agent/service.py`、`agent_service.py`、`rag_product_service.py` 引用部分、`frontend/main.jsx`）；**C** = B14（`llama_index_service.py`、`session_service.py`、三条编排入口的 `bind_session` plumbing）。lead 复审后补三处收尾：引用契约测试流式断言取**最后一个** sources 帧（每帧是累计全量、前端逐帧替换）、产品路径引用并入 `citations_from_chunks` 单一口径、一处 ruff import 排序。

修法定案（对照冻结决策）：

- **B9+B10 身份分层**：`rag_requests.id` 改为服务端受理生成的 `task_id`（uuid），客户端 `request_id` 独立成列、**会话内唯一**（`UNIQUE(session_id, request_id)`）；`request_id` 无会话的首问由部分唯一索引 `(user_id, request_id) WHERE initial_request=1` 兜底。`input_hash = sha256(JSON[NFC+首尾空白归一的问题全文, 附件文件名, 附件字节 sha256])`，`question` 列继续存脱敏文本——**展示/存储口径与身份口径分离**，B9 根因（拿脱敏文本当身份）消除；附件字节经 multipart 解析时的 `content_hash` 参与身份，B10 消除。`replay` 按 `(request_id, session_id)` 或 `(request_id, user_id, initial_request=1)` 定位后先校验 user 与 input_hash：不匹配或历史 NULL hash 一律 409 fail-closed；`finish`/`context`/`feedback`/issue 闭环全部改按 task_id（`rag_issues.request_id` 列值即 task_id，对前端不透明）。`ChatResponse`/SSE `start`+`done`/会话历史新增 `task_id` 字段，向后兼容。
- **B2 引用同源**：新增 `services/citation_service.citations_from_chunks`——按 `collected_chunks` 原序、不去重、不截断，与 `tools.py` 给模型发号的 `[n]` 一一对应；普通 agent、legacy 管线、产品路径三处统一调用。流式 `sources` 事件每帧携带累计全量列表（前端逐帧整体替换，最终态即完整集），前端来源按钮加 `[n]` 前缀与正文编号对齐。判级 `bound_evidence` 只缩片段正文不删片段，编号关系在收窄后仍守恒。
- **B14 会话锁 release**：`assistant_sessions.rag_release_id` 持久绑定——`SessionService.resolve_release` 首次有可用发布时条件更新（`WHERE rag_release_id IS NULL`）定唯一赢家，进程重启后仍有效；普通 AgentService、AgentOrchestrator（`_build_run`）、产品路径（agent 开/关两支）检索前 `bind_session`；绑定会话无活动 release 时 fail-closed 返回空，绑定版本配置指纹不匹配时不漂移、不降级读无版本全库。会话删除重建即新会话重新解析（`ensure_session` 语义不变）。`:412` docstring 改为与实现一致。
- **迁移 0012**：存量行回填 `request_id=id`、`initial_request=1`，`input_hash` 保持 NULL（原文不可还原 → 该部分历史请求拒绝重放，fail-closed）；`downgrade` 有意 no-op 保留身份与绑定数据（沿用 0008 先例）。`postgres_schema.sql`/`rag_schema.sql`/`sqlite.py`/`postgres.py` 的 init_db 幂等路径与 migration 同源同步。

| 项目 | 状态 |
| --- | --- |
| 范围 | 上列 A/B/C 三块 + 文档两处；B6–B8/B11–B13/W 项未动 |
| 实现/产物 | 已编写并经 lead 逐行复审；子 agent 按文件互斥拆分，共享文件（路线/架构/decisions/黄金集）归 lead |
| 自动验证 | 触及套件 `56 passed`；全量默认集 `708 passed / 96 deselected`（689+19 新增）覆盖率 86.47%；黄金集 `36 passed` 无回退；`ruff check .` 通过；前端 vitest `75 passed` + `npm run build` 通过。环境：本机 `.env` 在位、无导出变量裸跑。**PG 迁移实证**：scratch 库先套 HEAD 版 DDL + 遗留行 → `stamp 0011` → `upgrade head`：回填/双唯一索引/`rag_release_id` 均正确，`downgrade` 保数据、`re-upgrade` 幂等；`init_db` 在新旧库各跑两次幂等 |
| 场景验收 | **未做**真实浏览器/模型/live。B9/B10 反例由 `test_rag_identity.py` 确定性等价覆盖（同 request_id 不同围栏源码/不同附件字节/不同文件名 → 409；同输入重放 → 同 task_id/message_id；NULL hash → 409）；B2 由 `test_citation_contract.py`（同文件多片段 + 跨轮 7 片段，发号/响应/上下文/落库四处同源）；B14 由 `test_session_release.py`（跨实例/重启锁版本、并发 CAS 唯一赢家、指纹不匹配不漂移、删建重解析） |
| 用户验收 | 通过（用户授权 S3-a 推进；S1-c 验收同批确认） |
| 下一批授权 | 未授权 |

证据在本机 `.logs/s2_acceptance_20260922/`（notes.md + 六份日志 + PG 迁移实录），块级过程稿在 `.logs/s2_identity_20260922/`、`s2_release_20260922/`、`s2_citations_20260922/`，不随 Git 提交。

### 本批登记的新发现（均未修，不属本批范围）

| 编号 | 发现 | 归属建议 |
| --- | --- | --- |
| W11 | 客户端以伪造或丢失的 `session_id` + 复用 `request_id` 可绕过会话内唯一约束产生重复任务行（契约本就是「会话内唯一」，属不一致客户端边角；后果是多执行一次而非错答） | S3 恢复设计时复核 |
| W12 | 会话绑定后「无活动 release → 返回空」对 legacy 编排路径同样 fail-closed；此前未绑定实例 `release_id=None` 会读无版本全库。仅在「LI 有节点但无任何已发布 release」的退化部署上有差异 | 如实登记；S4 PG 收敛时一并定性 |
| W13 | 流式 `sources` 事件为累计前缀帧、前端逐帧替换——契约成立但 SSE 契约文档未显式写明该语义 | 低优先；契约文档化随后续批次 |


## 12. S3 批次拆分与 S3-a 批次记录（前置决策冻结）

用户 2026-09-22 授权按下列拆分推进 S3（顺序保持基线 §5：源码带外先于恢复实现，单独打补丁会被保管设计推翻）：

| 批 | 内容 | 前置 | 验收要点 |
| --- | --- | --- | --- |
| S3-a | decisions.md §3 六项冻结（本批，纯文档） | 无 | §3 清零或显式延期 |
| S3-b | B6+B8+B7 源码带外：执行输入改取任务寄存源码、落库 hash 与执行同源；普通路径消毒复用 `redact` 口径；同任务 `grade_plugin` 去重（N05） | §8 临时源码项 | B6 复述不一致反例转绿；重复评测不二次扣配额；`~~`/裸粘贴源码不进正文 |
| S3-c | B11+B12+B13 任务持久化与恢复：task_id 锚的步骤级状态（受理→检索→评测→生成→落库）；pending 重连接续而非只能 409；取消与断线分离登记；harness 只读任务查询接口（独立仓库子任务）；捎带 W7/W8/W9/W10 图与上下文边角 + W11 复核 | S3-b、§8 权限/结果未知/重连三项 | 故障注入矩阵：重连/后端重启/harness 重启/重复请求/取消/输入过期，逐项确定性替身验证 |
| S3-d | N02 结果按需讲解：正式作业结果交助教讲解，不重评不改成绩 | S3-c | 讲解引用既有 grading_submissions，不产生新成绩行 |
| S3-e | N08 离线 judge 落地 + 前端重连 UI（GET /api/tasks/{task_id} + SSE 续传/重放 + 刷新找回） | S3-c | 离线评估脚本可复现；在线 judge 存留按证据再定 |

### S3-a 批次记录（2026-09-22）

| 项目 | 状态 |
| --- | --- |
| 范围 | 仅 `decisions.md` §3 表格更新 + 新增 §8 + 本节；无代码改动 |
| 实现/产物 | §3 六项中五项全量冻结；「预算与容量」护栏冻结（单任务 10min 硬时限 + admission 复用），规模数字按最迟决策点延至 S4 前由用户给定 |
| 自动验证 | 不适用（文档批） |
| 场景验收 | 不适用 |
| 用户验收 | 通过（用户授权 S3-b 推进） |
| 下一批授权 | S3-b 在拆分方案内已授权，输入即 §8 冻结口径；开工时先复核 `tools.py`/`harness_grading.py`/`assignments.py` 三条源码入口 |

地基在手：S2 的 task_id/input_hash/会话锁 release 是恢复身份锚点；`rag_requests` 全生命周期行已落库，S3-c 是加状态粒度而非新建表。harness 侧无任务查询接口为已知缺口，列入 S3-c 子任务。

### S3-b 源码执行源带外 + 消毒统一 + 同请求评测去重（B6/B8/B7）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | `agent/tools.py`、`agent/service.py`、`services/agent_service.py`、`services/rag_issue_service.py` + 新测试 `tests/test_s3b_source_identity.py`；`test_agent_tools.py` 三处旧契约断言改写。无 schema 变更、无前端改动、无迁移 |
| 实现/产物 | **B6**：`AgentToolContext` 新增 `pasted_source`/`graded_sources`；`grade_plugin` 执行源三级解析 = 上传原件 > `_build_run` 从消息抽取的最长围栏块（`extract_fenced_source`，``` 与 `~~~` 同口径、含语言标注与未闭合尾围栏）> 模型参数兜底；MCP 入参、大小校验、`code_hash` 恒等于实际执行字节；`source_origin` 三值 `upload`/`pasted`/`model_args` 入结果与落库。**B7**：同请求内同 `(protocol, sha256(执行源))` 命中 `graded_sources` 直接回放并带 `deduplicated` 标记——先于配额扣减、MCP、成绩落库；失败/超时/超限不缓存可重试。**B8**：`redact()` 围栏正则扩展到 `~~~`；`_persistable_question` 无附件分支、`_sanitize_question` 降级预览、非产品路径 `ensure_session` 标题（复审补的残留面，四处调用点）统一 redact 口径。产品路径复用 `_build_run`，两路径同构 |
| 自动验证 | 触及套件 `111 passed`（新测试 12 + golden 36 无回退）；默认集 `720 passed, 96 deselected`，覆盖率 86.48%；`ruff` 全过。证据 `.logs/s3b_source_dedup_20260922/` |
| 场景验收 | 确定性替身验证：上传原件优先于模型复述（hash/MCP 字节一致）、围栏抽取执行源、`model_args` 兜底、去重先于配额、失败不缓存、`~~~` 抽取与消毒、落库正文无源码。live 冒烟、真实 harness 端到端、PG mark 未验证 |
| 用户验收 | 通过（用户授权 S3-c 推进） |
| 下一批授权 | S3-c（任务持久化与恢复：B11+B12+B13 + harness 只读任务查询接口）待用户验收 S3-b 后授权 |

### S3-c 任务持久化与恢复（B11/B12/B13 + harness 查询接口）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | 产品路径全量：`task_service.py`（新，注册表/订阅/取消/恢复）+ `api/tasks.py`（新，4 端点）+ `rag_product_service`/`chat.py`/`agent tools`/`main`/`user_service` 接线 + `rag_issue_service` 账本原语 + schema 0013（`rag_task_inputs`、`rag_task_jobs`）+ 配置 `RAG_TASK_MAX_SECONDS=600`/`RAG_TASK_INPUT_TTL_HOURS=24`；harness 仓 `server.py`+`test_jobs.py`（本地提交 `15b4a74`，未推送）。普通 agent 路径（无任务账本）不在本批——登记 W14 |
| 实现/产物 | **B11**：`probe` 三态（miss/in_progress/terminal/replay），pending 重提交按 task_id 接续而非 409；**B12**：受理即寄存 `rag_task_inputs`（原始问题+执行源，终态即清/24h 兜底），重启孤儿经 `recover_orphans` 重驱动——grade_plugin 确定性 `job_id` + `rag_task_jobs` 账本 + harness `get_task_status` 接续，已完成评测不重打 MCP、`claim_job_submission` 闸保成绩只落一次；**B13**：注册表托管执行（订阅断开不取消），取消只有显式入口 `POST /api/tasks/{id}/cancel` 与账号停用批消（reason 区分 user_cancelled/account_disabled） |
| 自动验证 | 触及套件 `134 passed`（新 9 例恢复矩阵 + 17 例账本 + golden 36 无回退）；默认集 `746 passed`、覆盖率 85.59%、ruff 全过；PG scratch 库真实迁移 stamp→upgrade→downgrade→再 upgrade 全通。证据 `.logs/s3c_task_recovery_20260922/` |
| 场景验收 | 替身矩阵覆盖：接续/断线续跑/取消幂等/账本复用/job 重投/寄存源字节一致/越权/超时/停号。真重启、真断线、多副本、PG 套件、live 未验证；前端重连 UI 归 S3-e |
| 用户验收 | 通过（用户授权 S3-d/S3-e 推进） |
| 下一批授权 | S3-d（N02 正式结果按需讲解）或 S3-e（N08 离线 judge + 前端重连 UI）待用户定夺；harness 仓推送待用户确认 |

### S3-d 正式作业结果按需讲解（N02）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | 后端：`POST /api/assignments/{aid}/submissions/{sid}/explain` + `GradingService.get_submission` + `AgentToolContext.explain_context` + `_build_run` 播种与讲解 prompt 块 + `_resolve_explain` 恢复重建 + `AssignmentSubmitResponse.submission_id`；前端：`client.js` 抽共用 SSE 解析 + `explainSubmission`/`getTask`/`streamTaskEvents` + `main.jsx` 结果卡「讲解」按钮与会话接续渲染。无 schema 变更、无迁移、无配置新增 |
| 实现/产物 | 确定性身份 `session_id=explain-{sid}`、request_id=uuid5 派生——重复点击命中同一 rag_requests 行：pending→attached、终态→replayed，不重复扣配额不重复执行。成绩行播种进 `grading_results`（`explained_submission` 标记），参考答案注入/升级档统计自动生效；讲解块声明历史成绩、优先讲错因、不主动重评、练习路径不改成绩。讲解任务即普通产品任务，SSE 走 `/api/tasks/{task_id}/events`；刷新自动找回归 S3-e |
| 自动验证 | 触及套件 `44 passed`（新 16 例）；默认集 `762 passed`、覆盖率 85.71%、ruff 干净；前端 vitest `85 passed`、build 通过。证据 `.logs/s3d_explain_20260922/` |
| 场景验收 | 替身验证：归属/错作业/不存在/不可见全 404、过期可讲、attached/replayed 幂等、无新成绩行、FAIL 触发参考答案注入、恢复重建正反用例。真实浏览器端到端、live/PG mark、并发双发 explain 未验证 |
| 用户验收 | 通过（2026-09-22 用户确认） |
| 下一批授权 | S3-e（N08 离线 judge + 前端重连 UI）待用户验收后授权；harness `15b4a74` 已推送 origin/master |

### S3-e 离线 judge + 前端任务重连（N08）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | 后端：`RAG_ONLINE_JUDGE_ENABLED`（默认 false）+ `validate_answer` 确定性/模型两段拆分 + `scripts/rag_judge_eval.py` 离线验收 CLI + `tests/rag_eval/judge_cases.yml` 标注题集；前端：`getActiveTask`/`cancelTask` + 会话切换活动任务找回 + 停止按钮升级显式取消。无 schema 变更 |
| 实现/产物 | **离线口径**：开关关 → `_candidate` 不传 judge 模型，`validate_answer` 只跑确定性四判据（empty/invalid_citation/no_evidence/missing_citation，顺序同在线现语义），全过即 answered/supported；**语义要点**：`judge_enabled=None` 解析为「配置或显式注入模型」——`api/rag.py` 教师手动原题验证与测试替身不受影响。离线工具按标注题集逐条对账 verdict，deterministic 例不打模型进默认集断言，judge 例供人工验收。请求登记/幂等/反馈路径未动。**前端重连**：会话切换/重登录后 `GET /api/tasks/active` 找回 pending 任务 → SSE 续传（守卫：视图/会话/世代号三重匹配，静默降级）；停止按钮 = `cancelTask` + 本地 abort（显式取消），确认 cancelled 才落终态 |
| 自动验证 | 默认集 `789 passed`（+27）、覆盖率 85.74%、ruff 干净；前端 vitest `91 passed`（+6）、build 通过；脚本冒烟 deterministic 例 2/2 PASS 不打模型。证据 `.logs/s3e_offline_judge_20260922/` |
| 场景验收 | 替身验证：关判四分枝 + 零模型调用断言、开判回归、`_candidate` 集成、原题验证入口不受影响、题集驱动断言、前端 API 契约与取消确认语义。**未验证**：judge 标注例实跑（需真实 LLM 凭据，手工验收步骤）、真浏览器刷新/取消端到端、live/PG mark |
| 用户验收 | 通过（2026-09-22 用户确认） |
| 下一批授权 | S3 阶段全部批次交付完毕；S4（规模/容量 + 在线 judge 存留证据决策）待用户验收后规划。遗留边界：取消占位消息重载后被过滤、sessionId 不落 localStorage（刷新后需点会话）、`done{pending}` 边例不轮询 |

## 13. S4 批次拆分（PG 收敛与试用验收）

依据 §1 S4 行「退出 SQLite 和无用旧入口、形成可试用版本」，前置决策已冻结（decisions.md §9）。批次拆分为 a→d 四批，逐批授权：

| 批 | 内容 | 依赖 | 放行/验收要点 |
| --- | --- | --- | --- |
| S4-a | 决策+证据批：§9 八项冻结落档；`rag_judge_eval.py` judge 标注例实跑出证据报告（需真实 LLM 凭据） | S3-e | decisions §9 成文；评估报告落 `.logs/` |
| S4-b | PG 收敛实施：删 legacy 引擎（RAG_ENGINE/ingest_repo/ingestion_service/retrieval_store/retrieval_service，`knowledge_chunks` 表保留）；产品路径收敛唯一（RAG_PRODUCT_ENABLED/AGENT_ENABLED 开关移除，装配失败 fail-closed 503）；W6 postgres mark 套件对齐显式测试库；W12 定性落地；W4「未提交」统一显示 | S4-a | 默认集+postgres mark 全绿；无 RAG_ENGINE/产品开关残留引用；历史引用解析不回退 |
| S4-c | 本机 compose 部署验收演练：真重启孤儿重驱动、真断线后续跑/续传、取消端到端、讲解端到端、备份恢复 drill、alembic 新库/升级链/stamp drill、postgres mark 套件、容量冒烟（≤10 并发问答） | S4-b | 逐项演练证据落 `.logs/`；失败项登记 |
| S4-d | 试用收口：在线 judge 存留按 S4-a 证据落地；完整教学场景走查（建作业→提交→讲解→成绩视图）；W2 关闭、遗留登记项清零或结转；文档终扫 | S4-c | 教学闭环场景清单逐项过；决策落档 |

批次纪律同 S3：每批独立验收、用户授权后开下一批；S4-c 是首个需要真实基础设施的批次，环境不可用则如实记未验证而非替身代替。

### S4-a 批次记录（决策落档 + judge 离线证据）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | 纯文档+证据批：decisions.md §9 八项冻结（用户问答逐项确认）、本节拆分表、`rag_judge_eval.py` judge 标注例真实凭据实跑。无代码改动 |
| 实现/产物 | §9 冻结：SQLite=测试/本地后端、legacy 引擎删（knowledge_chunks 表留）、产品路径收敛唯一（两开关移除、装配失败 fail-closed）、单副本确认、容量=单班 ≤60 学生/≤10 并发、W2 关闭、W4 修、judge 存留待证据 |
| 自动验证 | `rag_judge_eval.py` 两轮实跑（qwen3.8-flash，真实凭据）：deterministic 5/5 PASS 不打模型；judge 例首轮 2 PASS + 2 超时（30s 长尾）+ 2 判严，复跑 timeout=90 超时两例 6.6s/11.1s 判对。报告 `.logs/s4a_judge_eval_20260922/`（report.json + report_t90.json + 解读 notes） |
| 场景验收 | 证据结论：judge 清晰例判得准、边界例系统性偏严（needs_clarification/not_applicable→unanswered）、单次核验 5–11s 且有 >30s 长尾——**倾向维持离线口径**，存留决策留 S4-d 用户拍板 |
| 用户验收 | ✅ 已验收（2026-09-22 授权 S4-b 开工即验收事实） |
| 下一批授权 | S4-b 已授权并完成 |

### S4-b 批次记录（PG 收敛实施）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | §9 冻结落地：删 legacy 引擎（`RAG_ENGINE`/`ingest_repo.py`/`ingestion_service.py`/`PostgresRetrievalStore`）；产品路径唯一化（`RAG_PRODUCT_ENABLED`/`AGENT_ENABLED` 移除，`/api/chat*` 恒走 `RagProductService`，装配失败 fail-closed 503）；W6 显式测试库对齐；W12 定性消解；W4「未提交」显示。非目标：postgres mark 实跑/compose 演练（归 S4-c）、judge 存留决策（归 S4-d） |
| 实现/产物 | 60 文件、净删约 1200 行：`services/knowledge_sources.py` 新建（承接仍在服役的 reader/chunker）；`retrieval_store.py` 只留 Protocol + `get_historical_chunk`（`knowledge_chunks` 表保留，非 `li_` 历史引用经它回读）；`main.py` 恒 warm LI + ready 恒核 active release；`test_postgres_runtime.py` 删除（3 例全断言 legacy 数据面，覆盖由 `test_rag_versioned_postgres.py` 承接）；W4 后端 `earned: Optional[float]` + 前端 `isUnsubmitted`/「未提交」双列。登记项消解：W4/W6/W12/W14（普通 agent 路径随删除消解） |
| 自动验证 | `pytest -m "not live and not postgres"` **789 passed / 93 deselected**，覆盖率 87.75%，ruff 全过；前端 vitest 96 passed、build 通过；`-m postgres --collect-only` 6 例无 import 错误；无 `RAG_ENGINE`/`RAG_PRODUCT_ENABLED`/`AGENT_ENABLED`/`PostgresRetrievalStore`/`IngestionService`/`ingest_repo` 代码残留 |
| 场景验收 | **未验证**：postgres mark 6 例实跑（需 `RAG_TEST_DATABASE_URL`+已发布版本数据）、live mark、compose 部署、浏览器端到端——均归 S4-c 演练 |
| 证据 | `.logs/s4b_pg_convergence_20260922/notes.md` |
| 用户验收 | ✅ 已验收（2026-09-22 授权 S4-c 开工即验收事实） |
| 下一批授权 | S4-c 已授权并完成 |

### S4-c 批次记录（本机 compose 部署验收演练）— 2026-09-22

| 栏 | 内容 |
| --- | --- |
| 范围 | 演练批（几乎零代码变更）：四镜像全新构建起栈、迁移链 drill、postgres mark 实跑、故障注入矩阵、备份恢复、容量冒烟 |
| 实现/产物 | 仅一处代码修复：`test_rag_versioned_postgres.py` 清理顺序（`_initialize()` 先于 `DELETE data_*`，全新库可跑）。演练产物 `deploy/.env`+自签证书（gitignored）。**文档陷阱发现**：新库上 `stamp head` 静默漏建 users/api_tokens/grading_submissions——fresh deploy 必须 `init_db`→`upgrade head`，stamp 仅限存量库 |
| 自动验证 | postgres mark **6/6 全过**（compose PG17+zhparser，`RAG_TEST_DATABASE_URL` 专用库）；宿主机默认集 789 passed、ruff 干净 |
| 场景验收 | 逐项证据在 `.logs/s4c_deploy_drill_20260922/notes.md`：断线续跑（断开后 answered 69s）✓、显式取消（user_cancelled）✓、SIGKILL 孤儿重驱动（账本单行、成绩不重复）✓、作业→真实 harness 评测（9/9）→讲解→replayed 幂等 ✓、备份恢复（284K dump 全量还原）✓、迁移链三路径 ✓、10 并发 10/10 answered（5.8–22.5s）✓ |
| 未验证 | 浏览器端到端、live mark（需完整 ingest）、60 学生真实规模、多副本——归 S4-d/如实登记 |
| 用户验收 | ✅ 已验收（2026-09-22 用户显式确认） |
| 下一批授权 | S4-d（试用收口）待用户授权 |

### S4-d 批次记录（试用收口）— 2026-09-23

| 栏 | 内容 |
| --- | --- |
| 范围 | 在线 judge 存留决策落地（decisions.md §10，用户拍板维持离线口径）；完整教学场景人工走查（真实 compose 栈）；W1–W14 + S3-e 三遗留边界逐项定性清零或结转；文档终扫 |
| 实现/产物 | **前端**：`client.js`（`assistant_last_session` 持久化助手、`authProbeReady` 401 探测短路——W3）；`main.jsx`（会话持久化+自动找回、`done{pending}` 有界重试、显式清空路径）。**后端**：`session_service.py`（取消占位历史可见、会话记忆口径分离）；`context.py`（W10 变换保条数断言）；`service.py`（W9 合并块丢正文修复）。**测试**：`tests/test_s4d_closeout.py`（新）、`client.test.js`、`test_agent_service.py`（W9 回归） |
| 自动验证 | 后端默认集 795 passed / 93 deselected（794+1 新增 W9），覆盖率 87.81%、ruff 干净；前端 vitest 99 passed、build 通过 |
| 场景验收 | 真实 compose 栈逐项实测，证据 `.logs/s4d_trial_closeout_20260923/`：双账号表单登录（CDP 实拍）✓、教师建作业+PATCH 三项生效 ✓、学生 5 次真实 harness 提交 ✓、成绩三态区分（ftp=未提交 vs ssh=0.0 零分 vs dns/http=13.2，师生双侧浏览器实拍）✓、讲解 87 帧 answered/high+二次 replayed 幂等+grading_submissions 8 行不变 ✓、显式取消 user_cancelled+SSE 终帧 ✓、断线续跑（断 TCP 后 answered）✓、刷新 active 找回 running+重订阅续传 ✓、取消占位「（请求已取消）」历史/浏览器双可见 ✓、`assistant_last_session` 落盘+刷新自动找回 ✓、`done{pending}` 帧实捕（合成 pending 行）✓ |
| 走查中修复 | **会话持久化挂载顺序 bug**：persist effect `[sessionId]` 挂载首拍以 null 写空串抹掉存根，自动找回实际从未生效——单测只测助手函数未覆盖挂载顺序，浏览器实测暴露；修为「只写真值、显式动作点清空」，重验通过 |
| 未验证 | 多学生班级隔离走查、CSV 导出（S3 已验形状未变）、live mark（需完整 ingest）、60 学生真实规模、多副本、harness 重启中的在途作业 |
| 用户验收 | 已通过（2026-09-23 用户单独确认 S4-d） |
| 下一批授权 | 未授权 |

#### S4-d 补：60 学生规模 + 多副本演练 — 2026-09-22（用户授权：全量写实 + 观测并最小修复）

| 栏 | 内容 |
| --- | --- |
| 范围 | 60 真实 viewer 账号（`U20261000001`–`060`，admin 批量建）全量走「登录→看作业→并发 10 问答→10 人真实提交」；backend 扩 2 副本观测任务注册表跨副本行为并按「观测+最小修复」口径修复复测 |
| 实现/产物 | `task_service.py`：`heartbeat_owned()`+`heartbeat_loop()`（在跑任务每 10s 拨新 `updated_at`，`status='pending'` 条件）；`rag_issue_service.py`：`claim_recovery()`（pending 且仍陈旧才拨新的原子认领闸）；`_recover_row` 装配前先认领；`main.py` 挂心跳任务启停钩子。零 schema 变更 |
| 自动验证 | 默认集 799 passed（新增 `test_heartbeat_*`×3、`test_claim_recovery_atomic_first_wins`）/ cov 87.82% / ruff 干净；前端 vitest 99 passed |
| 场景验收 | 证据 `.logs/s4d_scale_multirep_20260923/`。**规模**：建号 60/60、登录 60/60（每 IP 10/min 防爆破下节奏 408s）、看作业 60/60、问答 50/60 内联完成（p50 80s，瓶颈在上游 LLM 时延）+10/60 得 409 等待超时（库内终态 generation_failed，失败诚实无卡死）、提交 10/10 各落一行（同码命中快路径，串行编译耗时未覆盖——如实记偏差）、backend 峰值 17%CPU/300MB。**多副本修复前实测**：B 订 A 在跑任务 >30s → 双执行（双份 LLM 消耗，终态条件更新保证库态不脏）；B 取消 A 任务 → 行 cancelled 不被覆盖。**修复后**：B 订在跑任务 → `done{pending}` 不重驱动；SIGKILL A → B 认领单份跑完 answered；心跳拨新实测可见 |
| 残留限制 | B 看在跑任务 status=pending（非 running）；跨副本 cancel 只终态化库行、属主烧完余量；admission 仍进程级（N 副本=N×配额）；>30s 事件循环 stall 窗口仍存在；nginx 无粘性、跨副本续传靠重试轮转到属主 |
| 未验证 | 多学生班级隔离走查、CSV 导出、live mark、harness 重启中的在途作业、>2 副本 |
| 用户验收 | 已通过（2026-09-23 用户单独确认 S4-d 补） |

#### 遗留登记项逐项定性（本批清零/结转，全部有归属）

| 项 | 处置 | 依据 |
| --- | --- | --- |
| W1 sqlite+LI 起不来 | **关闭**（S4-b 消解） | legacy 引擎删除后 sqlite 只剩测试/本地语义，「默认引擎回退不可用」组合不再存在 |
| W2 初始密码强制修改 | **关闭不修**（§9 冻结） | 温和提示（管理页「待改密」标记），不强制拦截 |
| W3 未登录探测日志噪音 | **关闭（本批修）** | 401 后 `authProbeReady` 短路，无令牌探测不再重复 |
| W4 未提交显示不齐 | **关闭**（S4-b 修 + 本批实测） | `earned` nullable + 「未提交」双列；走查实拍 ftp=未提交 vs ssh=0.0 |
| W5 旧 harness 镜像裸跑即退 | **关闭**（S4-c） | 四镜像全新构建，环境事项消解 |
| W6 postgres mark ambient env | **关闭**（S4-b） | 显式 `RAG_TEST_DATABASE_URL` 约定对齐 |
| W7 finalize 不可达边 | **关闭-保留** | 防御性兜底死代码，清理收益小、保留无害 |
| W8 合成 ToolMessage 计入 answered | **关闭-接受** | ~95 字符不占预算；无 finalize 质量影响证据，若未来暴露再议 |
| W9 合并块丢正文 | **关闭（本批修）** | `tool_call_chunks` 守卫删除，正文由 `if text` 自然过滤；回归用例在 `test_agent_service.py` |
| W10 变换保条数约定 | **关闭（本批固化）** | `prepare_messages` 各级变换显式断言保条数，违反即 AssertionError |
| W11 伪造 session_id 重复行 | **关闭-复核定性** | 本批实测确认 `ensure_session` 归一化（伪 id→新 `sess_`）；重复执行限调用者自身、无越权；「会话内唯一」契约成立 |
| W12 无 release 的 legacy 读取 | **关闭**（S4-b） | legacy 路径删除，定性消解 |
| W13 SSE 契约未文档化 | **关闭（本批成文）** | 帧契约写入 architecture_current.md §3 |
| W14 普通 agent 路径无账本 | **关闭**（S4-b） | 路径删除自然消解 |
| S3-e 取消占位重载被过滤 | **关闭（本批修+实测）** | 历史展示/会话记忆口径分离；库行/接口/浏览器三层实证 |
| S3-e sessionId 不落 localStorage | **关闭（本批修+实测）** | 持久化+自动找回；含挂载顺序 bug 修复 |
| S3-e done{pending} 不轮询 | **关闭（本批修+实测）** | 有界重试（pending→任务状态轮询→恢复续传）；帧契约实测 |

## 14. 总审查修复批次 1：备份排除临时执行输入（已实现并验收）

本批只修复总审查确认的 N07 备份留存缺口。非目标：任务准入、配额、成绩事务、作业讲解、多副本边界。2026-09-23 用户明确授权四批连续推进并于本日统一验收；当时的验收仅覆盖这四批，随后用户又单独确认 §13 的 S4-d 与 S4-d 补。各表的「下一批授权」行保留执行当时的放行记录。

| 栏 | 内容 |
| --- | --- |
| 范围 | `deploy/backup.sh` 的 PG 归档排除 `rag_task_inputs` 数据、操作手册与本记录；盘点默认备份目录既有归档。SQLite 仍仅作测试/本地后端，不改变其文件复制路径 |
| 实现/产物 | PG `pg_dump` 增加 `--exclude-table-data=public.rag_task_inputs`：保留表结构，归档不含任务存活期的原始问题和源码 |
| 旧备份核查 | 默认目录仅一份 `assistant-20260922T135955Z.dump`；核查 `rag_task_inputs` 数据行数为 0，无受影响归档需要净化替换。其他环境的备份目录不在本机盘点范围 |
| 自动验证 | `bash -n deploy/backup.sh`、`git diff --check`、默认 pytest 799 passed / 93 deselected / cov 87.82%、ruff 全过、前端 vitest 99 passed、build 通过 |
| 场景验收 | 隔离 PG 源库放入一行合成临时输入与一行哨兵：归档无 `rag_task_inputs` TABLE DATA 条目；还原后表存在、输入 0 行、哨兵 1 行。实际脚本对本机 compose 栈产出归档并还原到独立空库：临时表存在且 0 行，users=62、grading_submissions=18、rag_requests=86、rag_releases=1。三个隔离测试库已删除；临时归档已删除，保留不含源码的记录 `.logs/backup_retention_20260923/notes.md` |
| 回退 | 回退脚本改动会重新把临时输入装入归档；若必须回退，先停用旧备份任务，保留已净化归档并改用同等排除参数手工备份 |
| 用户验收 | 已通过（2026-09-23 用户统一验收四批） |
| 下一批授权 | 已授权连续推进；批次 2 可开工，不代表本批已通过验收 |

### 总审查修复批次 2：原子准入、日配额与异常恢复（已实现并验收）

本批目标：同键并发首发只受理并扣费一次；受理失败不留下无输入 pending；执行收口 DB 故障不清掉仍需恢复的输入。范围仅产品问答及讲解的受理/运行时、相应测试和文档。非目标：成绩账本、作业计分、前端、多副本所有权与 admission。回退为退回本批代码提交；回退会重新暴露上述窗口，应停止新受理后执行。

| 栏 | 内容 |
| --- | --- |
| 实现/产物 | 路由先占 admission 槽，`begin_admitted` 在一笔事务里提交唯一请求、临时输入、用户消息和日配额；唯一键败方按原任务接续并释放多占槽，空会话随之清理。寄存/配额失败整笔回滚；执行异常时只对已终态行清输入，pending 行保留供孤儿重驱动 |
| 自动验证 | 默认 pytest 805 passed / 93 deselected、覆盖率 87.85%；ruff 全过；前端 vitest 99 passed、build 通过；`git diff --check` 通过。日志 `.logs/review_fix_batch2_20260923/pytest.log`（本机 gitignored） |
| 场景验收 | SQLite 确定性故障注入：同会话与首问并发只一行/一扣；寄存失败与配额超限零假受理；finish 失败后 pending 输入留存；并发败方释放 admission 槽。真实 PG 并发受理和重启故障注入待后续专用环境复核 |
| 用户验收 | 已通过（2026-09-23 用户统一验收四批） |
| 下一批授权 | 用户已授权连续推进；批次 3 可开工，不代表本批已通过验收 |

### 总审查修复批次 3：成绩账本同事务与停号边界（已实现并验收）

本批目标：harness 已完成但成绩 DB 写失败时不把任务收成正常终态；新聊天成绩与作业账本标志同事务，停号后尚未落库的聊天评测和正式提交均不得写成绩。范围：0014 增量迁移及双库 DDL、评测工具与成绩服务、停号事务、只读旧账本核查脚本及对应测试。非目标：正式作业提交业务幂等、历史旧成绩猜测补写、多副本所有权/心跳/跨副本取消。回退：0014 不删成绩身份列；若旧二进制回退，须暂停新评测写入并优先前滚修复。

| 栏 | 内容 |
| --- | --- |
| 实现/产物 | 聊天成绩新增唯一 `task_job_id`；job 首次登记与 grading 日配额同事务，结果落库与 `submission_done` 同事务。DB 暂故障向上抛可恢复任务异常，保留 pending 输入与 harness job 结果，重驱动后补写；停号事务取消 pending 任务、删除其有 job 身份的聊天成绩，正式作业提交落库前也核查账号状态 |
| 自动验证 | 默认 pytest 811 passed / 93 deselected、覆盖率 87.75%；ruff 全过；前端 vitest 99 passed、build 通过；`git diff --check` 通过。日志 `.logs/review_fix_batch3_20260923/pytest.log`（本机 gitignored） |
| 场景验收 | SQLite 故障注入：成绩插入失败回滚账本标志，第二拍不重打 harness、只补一条成绩；首次 job 登记与 grading 配额同事务，超额时两者均回滚；停号清理 pending 任务的聊天成绩、正式提交拒写。PG 专用 scratch 库：新库 `init_db→upgrade head` 重跑幂等、模拟 0013→0014 升级保留旧成绩、真实 PG 成绩同 job 两次只一行且停号后删除，均通过；三个 scratch 库已删除。旧成绩无 job 身份，仅出清单不自动修复 |
| 用户验收 | 已通过（2026-09-23 用户统一验收四批） |
| 下一批授权 | 用户已授权连续推进；批次 4 可开工，不代表本批已通过验收 |

### 总审查修复批次 4：作业受理依据、讲解身份与前端找回（已实现并验收）

本批目标：正式提交受理期间作业修改不改变本次响应分值，历史成绩仍按当前项折算；讲解任务在作业改名后继续幂等；前端 pending 重试有真实循环测试并完成刷新找回浏览器场景。范围：0015 增量迁移及双库 DDL、作业提交/成绩元数据、讲解身份、前端重试与找回、当前文档。非目标：修改成绩历史折算政策、普通聊天身份契约、真实 LLM/harness 评测、多副本边界。回退：0015 保留 purpose 身份数据；若旧二进制回退，暂停新讲解写入并优先前滚修复。

| 栏 | 内容 |
| --- | --- |
| 实现/产物 | 作业及目标协议项由单条 SQL 一致读取，本次 points/资格/受理时间写入成绩结果元数据，汇总仍动态折算；讲解问题文本改为成绩行 ID 稳定串，0015 仅核实旧确定性身份后标记 `purpose=explain`，普通 chat 的 input_hash 仍严格；前端 pending 循环抽为可测函数，并同步更新恢复会话的 ref，避免 active 查询快于 React effect 时漏找回 |
| 自动验证 | 默认 pytest 813 passed / 93 deselected、覆盖率 87.68%；ruff 全过；前端 vitest 102 passed（新增 3 个实际重试循环用例）、build 通过；`git diff --check` 通过。日志 `.logs/review_fix_batch4_20260923/pytest.log`（本机 gitignored） |
| 场景验收 | SQLite 替身：评测期间 PATCH 分值，本次响应 20 分口径、成绩视图 40 分口径；改标题后讲解回放且普通聊天异输入仍 409。PG scratch 库从 0014 升 0015：旧讲解身份核实后标记，原 input_hash 不变。浏览器（browser-harness + 独立无凭据 Chrome，合成 API 帧）：刷新恢复 session，pending 首帧后再订阅，终态回答在页面可见；真实 LLM/harness 未调用 |
| 用户验收 | 已通过（2026-09-23 用户统一验收四批） |
| 下一批授权 | 本计划四批已全部实施，后续新目标须另行授权 |

## 15. 外接盘部署迁移 A 批次（2026-10-10）

用户授权 A「源数据与部署准备」，并选择新部署主库为宿主机 `assistant_dev`。范围：旧 VM 核查、三源独立备份、外接工作副本、属主管理与部署入口；非目标：新 VM 创建、数据库还原/升级、构建/部署、真实模型、正式切换、旧环境清理、commit/push。

| 栏 | 内容 |
| --- | --- |
| 实现/产物 | `deploy/external_disk.py`、`docker-compose.external.yml`、配置模板、拒绝路径回归；外接副本与三份双盘备份；详见 [A 批次记录](external_disk_batch_a_20261010.md) |
| 数据决策 | 用户确认 assistant_dev（3737 节点）为主库；宿主机旧库、旧 Compose 演练库独立归档，不合并 |
| 自动验证 | 默认集 828 passed / 93 deselected、cov 87.68%；ruff 通过；前端 102 passed、build 通过；部署拒绝路径 15 passed |
| 场景验收 | 本机磁盘/权限和 Compose 解析、归档目录和双盘 SHA256；新 VM、恢复/迁移链、真实产品链路未验证 |
| 回退 | 原宿主机 PG 和源目录保留；旧 Colima 已停止，恢复旧 Compose 时需显式启动已停止的应用容器 |
| 用户验收 | 待验收 |
| 下一批授权 | B「外接盘隔离演练」未授权，不自动开始 |

## 16. 外接盘迁移 B 批次（2026-10-10，按原镜像/旧代码复用）

用户授权 B，并明确改为不下载/不构建、复用原实例和原镜像旧代码。当前运行版本与当前源码不同，不能将本批记为源码发布。

| 栏 | 内容 |
| --- | --- |
| 实现/产物 | 原 dpdk VM 停机副本迁到外接盘，保留原资源参数；三应用镜像 ID 相同，PG 原容器保留；使用 colima-dpdk-ext context 和受检查入口 |
| 数据恢复 | A 开发库备份空库还原，27 表计数/26 业务表逐行摘要匹配；由原镜像升级 0011→0013；活动索引 3737 完整且指纹相同；旧 43 节点演练库单独保留 |
| 自动验证 | 原镜像导出代码在本机默认集 799 passed / cov 87.79%；当前工作区/入口 835 passed / cov 87.68%；ruff 通过；新增入口专项 22 项 |
| 场景验收 | 实际 HTTPS auth/隔离、合成任务四帧终态续传、MCP DNS 编译9/9、离线召回和131唯一历史引用、VM停启持久性、净化备份实际恢复通过；真实模型/质量/容量/浏览器交互未验证 |
| 维护限制 | PG 原镜像索引记录已缺失，容器完整可运行；入口只 start PG，不重新创建；后续重建方案须单独确认 |
| 最终状态 | 外接四服务 Running（backend/PG healthy）；原内置 VM Stopped；宿主机 PG 保留；临时测试数据已清理，原开发库账号仍0；详见 [B 记录](external_disk_batch_b_20261010.md) |
| 用户验收 | 待验收 |
| 下一批授权 | C 正式切换/D 清理未授权，不自动继续 |

## 17. 外接部署 C/D 批次收口（2026-10-10）

用户授权 C/D 连续执行并明确清理/链接范围及正式 leader 账号；原镜像旧代码保持，当前源码未发布。

| 栏 | 内容 |
| --- | --- |
| C 实现 | 最终源库停写对照；5库独立备份并逐库实际还原；指定正式leader与随机初始密码；宿主机PG停用；统一入口/默认Docker context切换 |
| D 实现 | 13233非缓存文件核对，全部历史证据/源码/Git/镜像/PG rootfs独立恢复包；三原路径链接安装；删除旧实体/旧PG数据，释放9.74GiB，内置可用约62GiB |
| 自动验证 | 依赖清理前836 passed / cov87.68%、ruff、23项入口专项、shell语法；B原镜像799历史结果独列 |
| 场景验证 | 清理后整机停启、ready=200、正式HTTPS登录/角色/退出、最终运行库实际恢复（用户1、成绩27、会话74、节点3737）；PG rootfs恢复包离线临时容器实际启动验证 |
| 最终运行 | 外接四服务Running，backend/PG healthy；旧代码schema0013；旧PG容器保留/冻结，不直接重建；无真实模型调用/质量容量结论 |
| 用户验收 | 待实际登录使用，不由授权自动推定 |
| 后续授权 | C/D范围已完成，其他软件升级、索引重建、模型费用测试未授权；日常命令/备份位置见 [C/D记录](external_disk_cutover_cleanup_20261010.md) |
