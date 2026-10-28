# 独立接手 README 静态走查

日期：2026-10-28。固定文档/实现快照 `437a8864e840a391f6b1c99480c6fb4af4ef549d`。按根README → backend-java/README → deploy-java/README → cutover-runbook → delivery入口阅读，并只读对照对应脚本/CLI。没有执行手册命令、构建、测试、PG、HTTP、浏览器或Docker操作；不是实际独立复现通过。

## 静态可复现问题

1. **[P2] 手动备份示例未绑定当前数据库。** `deploy-java/README.md`维护段和`delivery/OPERATIONS.md:39`直接给出backup.sh。`acceptance/deployment/backup_cli.py:16-19`在未导出 `DPDK_JAVA_COMPOSE_OVERRIDE` 时总选base库；而`rehearse.py:30-41`只在子进程/本地state保存升级或恢复overlay，不会导出回调用者shell。升级后照抄手动备份示例，可仍备份cold/base库，而容器内Java已经使用upgrade/restore库。应给出从本round可信state选择并validate当前overlay、检查dry-run.database等于活动backend目标再apply的步骤，或由包装器自动匹配且不一致即拒绝。实际窗口需验证base/upgrade/restore三阶段目标一致；不要因身份合法就视为选库正确。

2. **[P2] 首次新round的镜像登记与回退前置步骤不完整。** `prepare.py:17-23`创建空images manifest，未创建build目录；`cutover-runbook.md:47-60`只构建/登记candidate两角色。`images.py:30-32`随即save到尚未创建的build父目录，首次干净round缺少这项前置；其`:28-29`还要求唯一RepoDigests，手册未解释本地build后无该字段时如何生成/登记正确不可变引用。即使候选成功登记，新round仍缺p4-backend/frontend记录，而`rehearse.py:102-104`的应用回退依赖P4记录。应补owned归档目录创建、真实image ID/digest与存档一致性核验、P4旧归档的核对/load/登记路径；不得仅复制旧manifest或给同镜像改tag。现有主机已有build目录/P4记录不能代替首次接手流程。

3. **[P2] 环境准备依赖本机历史状态。** `deploy-java/README.md:15-16`和`cutover-runbook.md:31-32`硬编码`/Users/fsy/...`与旧daemonID；`guard.py:69-88`实际按当前HOME及现场daemon核验，其他用户/新profile照抄会被拒绝。应把本机记录标为历史事实，补显式新profile的创建/恢复责任、只读inspect/info取值与人工比对流程，保持拒绝旧context的护栏。`backend-java/README.md`的PG例子也仅引用主Agent已有实例和本地口令；首次使用者还需要取得明确的独立PG17.10/vector0.8.4与角色/目录身份资产，不能把缺环境时skip误当完整通过。

4. **[P3] 交付状态和部分操作前置说明滞后。** `cutover-runbook.md:3`、`delivery/README.md:3`、`HANDOVER_CHECKLIST.md`第4项仍称严格矩阵未通过/正式容量未执行，而同快照STATUS已登记J5-01工程通过及Python正式基线完成。应改为引用STATUS并区分Python完成、Java/双端待验，保留raw rc1与显式metrics政策。`OPERATIONS.md:33-37`虽说明retrieve/judge配置不足会拒绝/skip，但未给固定query向量配置或`/sources/cases.json`创建/挂载样例；当前prepare只生成知识源，Compose未传固定query配置，因此这些示例不是默认可执行正例。可明确标为预期拒绝测试，再另给显式合成fixture的正例。

## 已静态对齐的链路与限制

- Java21/wrapper、前端npm ci/test/build、四项TestPg显式配置及PG缺失会skip均有说明；Compose路径的Node22、标准库Python、Docker/证书工具仍需实际核对版本。
- 四个容器内知识路径与IndexWiring匹配；cold执行器包含全源预览→apply→ready及持久聊天/旧引用/SSE检查。CLI名称、--apply位置、backup_restore/rollback阶段参数与实现匹配。
- CLI默认dry-run，帮助不启动其Spring业务容器；operations-compose本身仍需已有Docker容器及守卫环境。独立PG不可用不回退旧库。
- 备份排临时数据保结构，恢复至另一owned空库，完整行/列结构摘要已接入；sequence/约束/索引不由行摘要单独证明。保留恢复资产、单写owner、真实上游/用户/生产边界清楚。
- health/ready与内网metrics语义正确；nginx拒绝外部metrics。手册没有一组可直接照抄的内部抓取及日志命令，下面列为实际接手验证步骤。

## 后续实际窗口的最小命令清单（本次全部未执行）

先关闭上述静态问题，由主Agent给出冻结checkout、独立PG四项配置、唯一新round/现有资产恢复路径与现场Docker身份。不要在当前容量独占窗口运行。以下均从获准仓库根目录执行，`JAVA_HOME`及凭据由本机owned配置注入；不打印env/token/private key。

```sh
# 1. 依赖/测试；完整PG结果必须0 skip，不能只看构建退出码。
java -version
node --version
python3 --version
./backend-java/mvnw -f backend-java/pom.xml -B verify
(cd frontend && npm ci && npm test && npm run build)
python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v

# 2. 仅在首次新round准备；已有资产沿用，不覆盖prepare。
# 按修正后的runbook完成prepare、现场身份、P4/candidate四角色与归档登记后：
scripts-java/compose.sh
python3 acceptance/deployment/rehearse.py --repo "$PWD" plan

# 3. 栈/索引/HTTPS/SSE/旧引用及四个真实部署阶段。
python3 acceptance/deployment/rehearse.py --repo "$PWD" cold --execute
curl --cacert "$PWD/.migration-artifacts/j5-03/certs/server.crt" https://localhost:18443/api/health
curl --cacert "$PWD/.migration-artifacts/j5-03/certs/server.crt" https://localhost:18443/api/ready
scripts-java/operations-compose.sh --help
scripts-java/operations-compose.sh ingest --manifest /sources/knowledge_sources.yml
scripts-java/operations-compose.sh purge-task-inputs
scripts-java/operations-compose.sh audit-grading-jobs --limit 20
python3 acceptance/deployment/rehearse.py --repo "$PWD" upgrade --execute
python3 acceptance/deployment/rehearse.py --repo "$PWD" backup_restore --execute
python3 acceptance/deployment/rehearse.py --repo "$PWD" rollback --execute

# 4. 当前overlay已显式绑定并核对目标后，再测手动备份dry-run/apply。
# 文件名必须全新；不得覆盖既有business-backup.dump或恢复资产。
scripts-java/backup.sh "$PWD/.migration-artifacts/j5-03/independent-manual-backup.dump"
scripts-java/backup.sh --apply "$PWD/.migration-artifacts/j5-03/independent-manual-backup.dump"

# 5. 仅内部抓metrics；Node来自现有固定替身容器，无需外部暴露后端端口。
scripts-java/compose.sh --apply exec -T fake-upstream node -e \
  "fetch('http://backend-java:18090/metrics').then(async r=>{if(!r.ok)process.exit(1);process.stdout.write(await r.text())})"
scripts-java/compose.sh --apply logs --tail 100 backend-java
```

逐步保留本次实际命令、源码/image/jar/源清单SHA、HTTP状态、PG/CLI前后断言、失败及资源收尾；日志仅留ignore。手工backup实际恢复、原生启动与React开发联调若纳入接手目标，另开同一owned域的小窗口，不以本清单静态匹配计通过。

**结论：静态走查完成；接手资料尚有上述4组待修/补前置，真实独立复现仍未执行，不能宣布J5-04实际接手验收通过。**
