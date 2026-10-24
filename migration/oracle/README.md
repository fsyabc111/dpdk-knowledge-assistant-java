# P0 Python 差分 oracle

调用本仓库冻结 `backend/` 真实业务逻辑，基线 `80b29135faddd16d5e39fdbf0633291ea6a20489`。`p0-v1` 修订2是历史基础fixture，黄金/manifest保持不变；两轴审查修复扩展到 `p0-v2`。当前p0-v2仅纯内存通过，动态采集、冻结和复审待正式长测退出后的授权窗口。Java尚未实现，真实模型/embedding/harness质量未验证。

## 显式版本命令

历史v1只读复验：

```sh
.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v1 --candidate .migration-artifacts/p0/candidate-trace.json
```

纯内存/CLI修复回归（不导入app、不连接PG）：

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m unittest migration.oracle.test_review_fixes -v
```

仅在主Agent确认长测退出后，顺序执行新v2动态采集：

```sh
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_python_oracle
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_candidate_oracle
.migration-artifacts/p0/venv/bin/python -m migration.oracle.freeze --fixture p0-v2
.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/p0/candidate-trace-p0-v2.json
.migration-artifacts/p0/venv/bin/python -m migration.oracle.check_account_disable --database p0_candidate_oracle
```

`run`重置指定专用库合成表，不得与同库或正式负载并发。运行产物仅在 `.migration-artifacts/p0`。v2尚未生成黄金时freeze应失败，不可用候选填补。

## 隔离与真实链路

`safety.configure`先清空ambient、禁用Settings.env_file，再导入app；验证显式URL/host/port/role/database/comment/data_directory，socket拒绝非回环出网。每连接 `PGOPTIONS=-c timezone=UTC`，不修改PG全局设置。空库走冻结init_db→Alembic0015→init_db，li表经PGVectorStore建立；已有库需head匹配。空库证据不代替schema0013升级演练。

ASGITransport不跑startup维护或真实预热。真实链为REST/auth→Session/RagProduct/Task→LangGraph→kb_search→HybridDpdkRetriever→RetrievalService→LiNodeRetrievalStore→PG1024维vector/FTS→RRF/MMR→确定性校验→事务保存。仅模型/embedding/MCP端口替身，不替换业务模型、权限、计分、受理或完成逻辑。真实客户端的HTTPX/MCP传输故障脚本独立验证重试、unknown和usage语义。

HTTP组使用固定逻辑UTC时钟，每读推进1微秒，源now_iso单调保护仍执行，monotonic耗时保留。专门PG事务组以固定wallclock消除12并发时间读取调度噪声；该组不验证消息时钟递增，HTTP组继续承担时间格式/顺序。时间不归一化，保留UTC/精度/局部时序及逐值比较。

## p0-v2 业务状态

`business_state.py`逐表显式SELECT非秘密业务列，存一次完整初态，每个HTTP与事务case记录各表实际removed/added行及前后count，最后完整终态。未变表也声明空delta；`validate_business_chain`可顺序重建每步前置状态。总行数与表hash不能替代业务值。

投影覆盖账号角色/树/enabled及令牌生命周期元信息，session/release/message字段指纹/metadata/citation rank，usage真实扣次，upstream usage_source/NULL/费用类型，assignment/items与成绩score/accepted快照，task status/purpose/input存在/长度/时限/jobs结果/submission_done/grade关联，knowledge/revisions。密码、令牌及其hash、运行源码、原始上游输出不导出。消息内容仅字段指纹与字节数，临时输入仅元信息；这些字段级指纹不替代其他业务列。

HTTP after明确为“消费响应并等待已受理任务收口后”的settled checkpoint；受理回执仍保持真实started形态。专门PG原语另测受理与完成失败回滚。逐请求前置状态由初态与之前delta重建，不能用后台调度竞态或删列消除差异。

源码标记按基线+版本在运行时确定性生成；实际输入字节的sha256须与PG成绩/受理身份相同。code_hash/input_hash不归一化，即使全部关联一致替换也失败。固定NFC/strip摘要向量逐值比较。

10项PG关键结果、清理checkpoint、raw1024向量顺序/RRF/MMR分数、六条同文件引用内容进入可比较trace。当前分数exact；观察性1e-12未启用为容差，未来需要用户确认与新manifest明示。citation.round4和业务得分始终exact。

## 比较器与冻结

CLI必选`--fixture`；通过0、业务差分1、manifest/JSON/schema/资源上限/非法日历2，稳定JSON错误输出。先验证trace容器和嵌套深度，再比较业务值；候选不能提供归一化占位符。

ID先按字段和实体命名校验，再作全trace双射：task为32hex，session为sess_12hex或explain-grade_16hex，message为32hex/msg_12hex，assignment为asg_16hex，grade为grade_16hex，knowledge为32hex。固定用户和调用者request_id保留。跨类型替换即使全关联一致也失败。对象key稳定遍历；数组不重排。

SSE按冻结React `frontend/src/api/client.js:162`的LF空行分帧和首event/data行语义解析，EOF不flush未终止尾帧；CRLF/CR失败。允许UTF8任意分块。sources累计前缀/身份关联保持；成功done、pending/terminal:false和合法error结束流分别判定，不能把error当done成功。结束后事件、坏done、缺结束帧和截断done失败。

freeze仅接受p0-v2及固定Python trace路径，无候选输入参数；存在即拒绝覆盖。p0-v1历史黄金不重写。manifest按文件SHA256保护，实际归一化路径与格式表独立审阅。

COMP003批准的Java安全目标仍在 `migration/fixtures/p0-approved-differences/comp003.json`，不改Python实际重投黄金；Java目标尚未执行。28条完整重试/并发分支与全认证角色组合仍有未执行项，不能把基础覆盖宣称全功能用户验收。修复证据见 `docs/java-migration/evidence/J0-02/review-fixes.md`。
