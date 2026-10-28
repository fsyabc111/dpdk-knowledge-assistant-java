# 运维与恢复资产索引

唯一隔离部署/升级/恢复/回退流程是 `docs/java-migration/cutover-runbook.md`；这里仅提供入口与资产索引，不维护第二套切换步骤。运行前沿用该手册的专属env、context endpoint/daemon身份和owned project。旧环境、旧.env、原数据库和真实上游不在本轮操作目标。

## 构建与验证命令

| 目的 | 仓库根目录中的实际入口 | 验证范围 |
|---|---|---|
| 后端纯测试 | `cd backend-java && ./mvnw test`，显式Java21 | 无PG配置时PG门禁用例跳过，不能当PG通过 |
| 后端实际PG测试 | 同一命令并显式提供TestPg四项隔离配置 | 由主Agent统一排队；先核对实例身份，禁止占用别卡PG |
| 打包 | `cd backend-java && ./mvnw -DskipTests package` | 仅构建，不代表场景验收 |
| 前端 | `cd frontend && npm ci && npm test && npm run build` | 单测与静态构建；浏览器闭环另验 |
| 部署准备策略 | `python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v` | 守卫/owned/全源/恢复命令策略，无实际stack |
| 静态配置 | `scripts-java/compose.sh` | 默认只展开配置，无daemon/PG写入 |
| 场景计划 | `python3 acceptance/deployment/rehearse.py --repo "$PWD" plan` | 明确缺失依赖与pending，零Docker/PG动作 |

J5-03实际执行命令与资源窗口只按既有runbook。真实模型、embedding或harness测试不由以上命令自动授权。

## Java维护入口

CLI启动强制关闭自身web/启动迁移/后台恢复。以下写操作默认dry-run，明确追加`--apply`才写入；PG不映射宿主端口时使用容器内CLI。帮助/非法参数不启动业务容器。

```bash
scripts-java/operations.sh --help
scripts-java/operations-compose.sh --help
scripts-java/operations-compose.sh create-users --id U20260000061 --roles viewer
scripts-java/operations-compose.sh purge-sessions --retention-days 30
scripts-java/operations-compose.sh purge-task-inputs
scripts-java/operations-compose.sh retain-issues --retention-days 30
scripts-java/operations-compose.sh ingest --manifest /sources/knowledge_sources.yml
scripts-java/operations-compose.sh rollback --release '<合法历史release ID>'
scripts-java/operations-compose.sh audit-grading-jobs --limit 200
scripts-java/operations-compose.sh retrieve --query 'dns' --top-k 5
scripts-java/operations-compose.sh judge --cases /sources/cases.json
```

上表retrieve/judge当前为配置缺失时的拒绝/skip示例：prepare不生成/sources/cases.json，Compose不默认注入固定查询向量。实际固定fixture正例见同jar CLI验收证据，不要直接把缺文件的示例当成功操作。retrieve/judge所需固定向量或离线fixture必须显式配置；未知/未配置即失败或skipped_live，不默认外发。judge固定样例不是live质量。索引必须提供完整合成快照及四个显式路径，不能把空索引视为成功。用户创建示例仅是合成账号，不能混入真实业务数据。

升级/恢复后先核对本round scenario-state当前overlay、实际backend DB_NAME与dry-run.database一致，不可默选cold/base库。备份入口默认dry-run：`scripts-java/backup.sh "$PWD/.migration-artifacts/j5-03/manual-backup.dump"`；实际备份追加`--apply`，先核对owned目标。只排rag_task_inputs数据、保留表结构，并检查dump TOC。恢复必须对另一owned空库，完整执行与断言见唯一runbook。不要手工给模板替换旧数据库URL。

## 恢复资产清单

| 资产 | 位置/证据 | 处理边界 |
|---|---|---|
| P4后端/前端不可变image | p4-images.json中的digest ID；本地新Docker profile | 保留，不只换tag冒充版本 |
| P4 image保存归档 | 本J5-03工作树 `.migration-artifacts/j5-03/build/p4-*.image.tar` | 保留；每次使用核对记录SHA |
| P4 jar/静态资源摘要 | `docs/java-migration/evidence/J5-03/p4-images.json` | 绑定真实归档内容 |
| P5候选image/归档 | 本round deployment-manifest记录，当前pending | 冻结候选后真实构建并记录 |
| round/project/image manifest | `.migration-artifacts/j5-03/deployment-manifest.json` | 600、Git忽略；不得覆盖既有恢复记录 |
| owned数据库manifest | `.migration-artifacts/j5-03/owned-databases.json` | 创建/恢复时才生成；当前实际场景pending |
| 原始DB备份 | `.migration-artifacts/j5-03/business-backup.dump`等 | 当前pending；本地600，不提交 |
| 合成源及manifest | `.migration-artifacts/j5-03/sources/`，非秘密摘要见evidence/J5-03/source-snapshot.json | 只读挂载，禁止复制真实源冒充合成样本 |
| env/TLS key/合成验收账号恢复状态 | 专属runtime.env、certs、scenario-state.json | 600、Git忽略；不打印/提交 |
| 实际场景断言/失败日志 | scenario-results.json、command-failure.log | 当前pending；提交前只抽取脱敏断言，日志留ignore |
| 原Python仓库、旧镜像、备份/部署 | 原项目恢复资产 | 本轮只读，禁止停旧/删除/退役 |

健康、ready和指标的含义：health为存活；ready还核对数据库/active release/声明成员/物理节点/配置指纹。metrics只供内网，nginx对外拒绝/metrics及/actuator；正式领域指标为进程counter/histogram，HTTP按源Content-Length与完整异步生命周期观测；旧持久gauge仅诊断。unknown单独计数，不能把HTTP次数当作模型或评测次数；实际业务hook/fresh矩阵验收以当前证据为准。
