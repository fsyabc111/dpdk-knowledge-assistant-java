> 历史归档：2026-09-18。原路径：`docs/rag_handoff.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# RAG 产品化开发交接

交接日期：2026-09-05。交接范围：本轮 RAG 产品化代码、测试、配置示例与前端工作台。

## 当前结论

已完成第一轮产品化实现与离线验证，**尚未达到可直接上线的验收状态**。`RAG_PRODUCT_ENABLED` 默认关闭。本轮没有执行生产迁移、生产索引、生产部署或真实模型付费评测。推送代码不代表完成上线。

进展记录见 [rag_product_progress.md](rag_product_progress.md)，路线图见 [rag_roadmap.md](rag_roadmap.md)。本文是接手入口，代码是实现现状的最终依据。

## 必须保持的决策

- 面向 DPDK 教学，共享知识库，目标为 20 路并发聊天；当前并发控制仅适用于单 worker。
- RAG 业务链不使用 hash 做校验、身份、去重或缓存键；节点使用 UUIDv4，增量扫描直接比较正文、元数据与配置。密码、token 与评测安全用途的既有 hash 保留，不能一并移除。
- 证据优先，资料不足允许拒答或澄清；正文验证并落库后才展示，工具进度仍可流式发送。
- 未解答问题脱敏保存 180 天，老师处理本班，组长处理全部；脱敏规则不能替代人工隐私审核。
- 老师可直接发布共享知识，但必须显式确认全体可见及隐私检查；发布记录成功不等于索引已生效。
- 先测真实模型单次成本，再申请批量预算。不要在交接后直接启动大规模付费压测。

## 实现入口

| 范围 | 文件与职责 |
| --- | --- |
| 数据库 | `backend/app/storage/rag_schema.sql` 与 `backend/alembic/versions/0008_rag_product.py`：发布版本、请求、未解答问题、问题事件、共享知识及修订 |
| 索引 | `backend/app/services/rag_index_service.py`：全源快照、实际内容比较、UUID 节点、embedding 后 CAS 原子发布、旧版本保留与回滚 |
| 检索 | `backend/app/storage/li_retrieval_store.py`、`backend/app/services/llama_index_service.py`：同一发布版本上的 LI 单数据面召回；向量数据面目前要求 PG + pgvector |
| 回答 | `backend/app/services/rag_product_service.py`：请求登记、图编排、证据判断、正文展示前事务保存、幂等重放、排队与超时 |
| 问题闭环 | `backend/app/services/rag_issue_service.py`、`backend/app/api/rag.py`：反馈、班级隔离、认领/合并/备注、原题验证、知识发布与修订 |
| Agent 护栏 | `backend/app/agent/graph.py`、`tools.py`、`backend/app/core/upstream_gate.py`：引用编号、工具上下文与 token 护栏、上游并发 |
| 界面 | `frontend/src/RagWorkspace.jsx`、`main.jsx`、`api/client.js`：问题工作台、共享知识、回答反馈、请求 UUID 与就绪状态 |
| 运维 | `backend/scripts/rag_maintenance.py`：默认只读统计，显式参数清理或回滚；`deploy/auto_ingest.sh`：不再按 Git HEAD 跳过扫描 |

业务 DDL 同时用于 SQLite 和 PG，不代表新的 LI 向量检索支持 SQLite。0008 的 downgrade 有意保留数据，不是删除业务表的恢复工具。旧索引节点保留是为了历史引用，不要直接清空 LI 表。

## 验证与复现

交接提交前重新验证：后端 295 例通过、91 例排除、覆盖率 77.77%；前端 63 例通过、构建通过、Ruff 通过。独立临时 PG + pgvector 测试已验证索引复用、发布冲突、历史引用与回滚，测试容器已删除，现有部署库未改动。

浏览器检查采用模拟教师接口，覆盖 1440×1000 与 390×844 工作台，没有脚本错误或横向溢出；这不构成真实后端端到端验收。临时截图和工具在 `/tmp`，不随 Git 交付。

标准开发环境使用 Python 3.11，安装仓库 requirements 后运行：

```bash
cd backend
python -m pytest -m 'not live and not postgres' --cov=app --cov-fail-under=60
python -m ruff check .
cd ../frontend
npm test
npm run build
```

当前主机后端 `.venv` 是 Windows 布局，离线验证使用已有 `dpdk-assistant-backend:latest` 镜像、代码挂载和 `/tmp/rag-product-test-tools`；容器关闭网络，模型地址指向不可用回环地址。测试工具 pytest 8.3.5 / pytest-asyncio 0.25.3 / pytest-cov 6.0.0 与仓库开发 pin 不同，后续应在标准 CI 再验证。

PG 专项测试是 `backend/tests/test_rag_versioned_postgres.py`，必须显式设置 `RAG_TEST_DATABASE_URL` 指向专用空测试库，禁止指向生产库。产品候选题在 `backend/tests/rag_eval/product_acceptance.yml`，共 100 题，尚未经教师标注；验收工具 `product_acceptance_runner.py` 有 list / run / 合并 / 汇总四模式（2026-09-13 升级）：默认只列题（支持 `--case/--expected/--sample` 确定性分层抽样），`--run` 才真实调用（令牌由 `RAG_EVAL_TOKEN` 提供、只进请求头不落盘），结果落盘 `backend/data/product_acceptance/`（已 gitignore），人工评分经 `--input/--scores` 合并、`--summarize` 汇总失败分类；auto 自动分类仅为初判，不构成人工验收通过，详见 runbook「产品答案验收」一节。

## 接手优先级

以下是待核查或待完成项，不能理解为已修复：

1. ~~上线阻断项~~（已修复，2026-09-12）：`/api/ready` 现经 `release_health` 核对发布版本的声明/成员/物理节点计数与 embedding 配置指纹（失败 503 + `ready_check_failed` 日志）；检索在固定版本时校验配置指纹，不匹配 fail-closed（`rag_release_config_mismatch` 事件）；`rollback_release` 强制校验配置匹配与节点完整性。
2. ~~请求边界~~（已修复，2026-09-12）：幂等重放不再扣每日配额（配额计数移到重放判定之后）；相同 request_id 携带不同问题返回 409；prepare 阶段有整体超时（`RAG_PREPARE_TIMEOUT_SEC`，默认 15s），超时残留的 pending 请求由周期维护归类 system_error。均有默认集回归测试（`tests/test_rag_product.py`）。
3. 问题闭环并发（部分修复，2026-09-12）：反馈×原题验证竞态已修——验证落库改为 `mark_verified` 条件更新（updated_at 乐观锁），验证期间到达的反馈使验证 409 拒绝、不再互丢更新；feedback 清空旧验证为预期语义。**接受的残余风险**：班级变更与权限检查之间的 TOCTOU 窗口未修（窗口为单次请求内的毫秒级，后果至多是一次越权完成中的更新，评估可接受）。
4. 内容安全：知识源敏感文件排除策略、跨文件扫描一致性、大幅缩库保护、自然语言隐私脱敏、数字数组误判引用均需补充测试。单次模型判断不保证答案正确。
5. 成本与稳定性：补齐 rewrite、embedding、judge 的用量汇总和实际费用、RPM/TPM、排队与工具耗时监控。现有 token 护栏不等同于全链路成本预算。
6. 真实验收：教师标注 100 题、盲审事实与引用、检索基线对照、授权后的 20 路持续 30 分钟测试、429/超时演练、备份恢复与首班灰度。
7. 后续增强：引用保留期驱动的旧节点清理、容量告警、邻接片段证据预算、问题统计/导出和回归题晋升。HNSW、中文分词与 rerank 先做对照实验，不作为默认升级。

## 上线与回退交接

1. 保持产品开关关闭，确认 `AUTH_MODE=account`、单 worker、知识源与隐私范围，备份并在独立环境演练恢复。
2. 在批准的部署窗口执行 migration 0008；首次安装按项目流程初始化数据库。不要用 `stamp head` 替代真实 schema 升级。
3. 获批 embedding 预算后运行 `python scripts/ingest_llama_index.py`。首次 UUID 索引需要重新嵌入；记录发布 ID、模型配置与节点数。
4. 完成上述阻断修复及验收，再开启 `RAG_PRODUCT_ENABLED=true`，检查 `/api/ready`、真实问答、历史引用、反馈和问题处理权限。
5. 自动扫描推荐每 5 分钟，观察未解答积压和索引状态；`/metrics` 只能内网访问，不加入 nginx 公网反代。
6. 索引回退命令为 `python scripts/rag_maintenance.py --rollback-release ID`，执行前必须人工确认目标版本完整且与当前 embedding 配置一致。应用回退需恢复上一已验证镜像/代码，单独关闭产品开关不能撤销本轮共享检索改动。

维护脚本不加参数只读；`--apply-retention` 会实际清理，不应作为首次排查命令。本次交接不授权后续代理操作生产或使用新增模型预算。

## 提交边界

本次提交包含 RAG 改造及本文，不包含原先已存在且与本任务无关的未跟踪文件 `deploy/update_wsl.sh`、`docs/deployment_linux_server.md`。真实 `.env`、密钥、测试数据库、浏览器临时资产不纳入版本控制。
