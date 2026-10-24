# P0 Python 差分 oracle

此目录调用本仓库冻结 `backend/` 的真实业务逻辑。功能基线为 `80b29135faddd16d5e39fdbf0633291ea6a20489`，fixture 为 `p0-v1`（冻结修订 2）。Java 尚未实现，`p0_candidate_oracle` 本轮运行同一 Python，证明比较器的正例可用。真实模型、embedding 质量与真实 harness 未获授权，均未验证。

## 唯一重跑入口

主 Agent 提供 `.migration-artifacts/p0/venv` 与 PG17.10/vector0.8.4。确认没有性能测量占用后，在仓库根目录依次运行：

```sh
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --database p0_python_oracle
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --database p0_candidate_oracle
.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --candidate .migration-artifacts/p0/candidate-trace.json
.migration-artifacts/p0/venv/bin/python -m migration.oracle.selfcheck
```

`run` 会重置其指定专用库的合成表，不能与同库其他运行并发。两个数据库互相独立。运行前验证 URL、host、port、role、database comment 和 data_directory；拒绝默认库、localhost别名、非回环、其他角色、URL附加参数。先清空 ambient，再禁用 `Settings.env_file`，最后才导入应用；socket guard 拒绝非回环出网。每连接 `PGOPTIONS=-c timezone=UTC`，不修改 PG 全局时区。

空库走冻结 `init_db → Alembic 0001..0015 → init_db`，li物理表通过冻结 `PGVectorStore` 建立。该证据是空库到当前schema，不是 schema0013 升级演练。已有库必须处于 `0015_explain_purpose`；运行再核验29表与关键唯一索引。

ASGITransport 显式不跑应用 startup/background maintenance；schema由入口建立。完整进程重启、硬期限与性能由独立性能worker记录。

## 真实逻辑与替身边界

真实链为 REST/auth → Session/RagProductService/Task → LangGraph → kb_search → HybridDpdkRetriever → RetrievalService → LiNodeRetrievalStore → PG 1024维vector/FTS → RRF/MMR → 确定性校验 → 事务落库。只替换模型构造端口、embedding端口和MCP调用端口；不替换业务模型、受理、权限、计分或任务完成逻辑。

固定语料包含同文件两个片段、历史legacy引用和三条1024维单位向量。语义并列沿冻结SQL `node_id ASC`，RRF/MMR记录原排序和score；当前同机同依赖差分逐值比较score，算法实测报告另记 `1e-12` 观察容差，不自动放宽比较。六条同文件引用直接调用真实 `citations_from_chunks`，确认不截断。

上游故障通过真实 `OpenAICompatibleClient` 的 HTTPX MockTransport 和真实 `HarnessMcpClient` 的传输/会话替身执行：工具调用ID、多片段、坏JSON、断流、429、timeout、usage缺失/重复、建连/initialize失败、提交后响应丢失和job finished/running/unknown/账本清空。账本不保存源码或模型原始请求。

固定业务时钟起点 `2026-10-24T04:00:00+00:00`，每次读推进1微秒，保留原monotonic耗时。时间字段逐值比较，校验UTC、0或6位微秒、created≤updated和expires>created，不以删除时间断言消除差异。

## 黄金与比较规则

黄金在 `migration/fixtures/p0-v1/python-golden.json`；manifest逐文件SHA256保护。`freeze` 无候选参数，只读固定的 `python-trace.json`，已有黄金即拒绝覆盖。修订2由主Agent审查要求固定对象key遍历后，仅从原Python trace重冻；旧草稿仍保存在运行产物目录，manifest登记上一摘要。后续扩展须新fixture版本并经owner审阅。

`verify` 只读黄金和候选：通过退出0、差分退出1、manifest/输入损坏退出2。候选不能提供 `<identity:n>` 或 `<duration>` 占位符。仅显式声明实体字段且符合生成格式的ID可双射归一化，调用方固定request_id保持原值；跨响应、帧和表引用必须一致。对象key稳定排序，数组顺序不改。实际154个归一化路径见 `normalization-review.json`，其中无时间字段。

SSE按UTF8增量及事件解析，允许传输分块不同；按冻结 `frontend/src/api/client.js:162` 的EOF行为，未由空行终止的尾帧不flush。done JSON闭合但空行前截断不能成为终态；校验sources累计前缀、身份关联、done后无事件。实测常规成功done没有terminal；数据库终态重建附terminal:true，pending必须显式terminal:false。各类完整字段仍精确比较，不能把这三种帧合并成同一种。

## 证据边界

每端184条HTTP观测映射66条路由的成功分支；account模式52条无权反例、44条无效输入/资源反例，14/22条分别有明确不适用理由。最小local/token向量另测，不代表66端点全部认证组合。有效token转停用的独立PG向量尚未覆盖；已覆盖logout令牌在停用/恢复后不复活。

路由retry/concurrency栏：4项引用已执行PG任务原语，34项只读不适用，28项仍未执行，明确交给对应业务卡/J5-01扩展。逐路由数据库副作用目前由最终快照、专门PG事务断言及上游调用计数支撑，未为每条路由单独采集完整表级前后快照。不能将此基础覆盖称为全功能场景验收。

10个PG专例覆盖12并发受理、受理回滚、真实FK导致完成事务回滚、完成一次/重放、取消幂等/取消后成绩禁写、12并发job成绩去重、12并发恢复认领、24h输入窗口/到期、1024维排序/RRF/MMR、六引用保持。源码运行输入仅运行时随机生成，长期表扫描零命中，收口零pending/零临时输入。

合成性能/恢复证据由 `performance/` worker独占，不由本入口冒充；阈值需要实测和用户确认，真实质量保持未验证。已知兼容差异见J0-02证据目录的 `known-differences.md`。

批准的Java安全差异COMP003单独保存于 `migration/fixtures/p0-approved-differences/comp003.json`，不改变Python黄金；running只等/查，unknown或查询失败不再提交grade。有效token停用补验命令：`python -m migration.oracle.check_account_disable --database p0_candidate_oracle`，须正式长测结束后执行。
