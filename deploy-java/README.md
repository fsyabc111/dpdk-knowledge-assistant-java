# Java 独立隔离演练入口

本物料属于 J4-03。静态配置检查不代表部署、恢复或用户验收通过。J5-03 使用独立 fake 上游演练；真实模型、真实 harness、旧实例切换与退役不在本批次。

## 准备与核对

- 运行目录：此仓库根目录；Java 21、Node 22、Docker Compose v2。主后端与维护入口不依赖 Python 主后端；Python 冻结 oracle/harness 另按任务使用。
- 必须由集成者先建立全新 Docker/Colima profile/context；禁止使用 `default`、`colima`、`colima-dpdk-ext` 或原 `dpdk-assistant`。脚本不会启动、切换或修改 Colima。
- 新栈 project=`dpdk-java-isolated`，宿主入口只绑定 `127.0.0.1:18443`，数据库/后端/fake 均无宿主映射；卷/网络自动附带此 project 前缀。不要用 `--project-name` 改为旧项目名。
- 外部源只接受显式绝对根，挂载只读 `/sources`。容器配置显式固定 knowledge-config=/sources/knowledge_sources.yml、dpdk-root=/sources/dpdk、assistant-root=/sources/assistant、working-directory=/sources；宿主快照必须具有此布局且容器UID10001具备只读权限，挂载不意味着自动 ingest。CLI用 DPDK_JAVA_KNOWLEDGE_CONFIG / DPDK_JAVA_DPDK_ROOT / DPDK_JAVA_ASSISTANT_ROOT / DPDK_JAVA_INDEX_WORKING_DIRECTORY 四个非空绝对路径，--manifest仅覆盖第一项；不猜父目录。

```bash
mkdir -p .migration-artifacts
cp deploy-java/.env.example .migration-artifacts/java-isolated.env
scripts-java/generate-cert.sh "$PWD/.migration-artifacts/java-isolated-certs"
```

修改新 env 文件的口令、源根、证书根及脚本输出的非 root UID/GID。私钥保留 0600，代理使用证书所有者 UID/GID 读取挂载。不得提交 env、证书或私钥。不要 `source` 旧 `.env`。

```bash
export DPDK_JAVA_ENV_FILE="$PWD/.migration-artifacts/java-isolated.env"
scripts-java/compose.sh                     # 默认只做配置展开，零容器操作
export DPDK_JAVA_DOCKER_CONTEXT='<本轮独立 context 名>'
export DPDK_JAVA_DOCKER_ENDPOINT='<只读核对所得 endpoint>'
scripts-java/compose.sh --apply build
scripts-java/compose.sh --apply up -d
```

`compose.sh --apply` 先只读核对 context endpoint、项目名及非 root 代理。只启动主应用可使用：

```bash
scripts-java/compose.sh --apply up -d postgres-java backend-java frontend-java
# 固定fake服务默认启用，backend依赖其健康；真实harness profile仍关闭。
```

主后端默认不配置任何真实模型。合成替身路由为 `http://fake-upstream:18091/v1/embeddings` 和 `/v1/chat/completions`；固定 1024 维向量和固定回答只用于部署路径。通过本轮显式配置 `dpdk.model.*` 后才可使用；缺配置不能当质量通过。替身现在提供 `/mcp` 的五种固定工具，grade_plugin只返回合成PASS，不编译/执行/保留源码；进程内job固定结果支持同job回放和unknown状态。它不是live质量或真实harness恢复验收，详见 fake-upstream/README.md。已提供 `harness-java` 的 `harness-authorized` profile：只接受另行审查的本地镜像（pull_policy=never），UID10001、read_only、tmpfs、资源受限且无宿主端口，使用 `java-harness` 专网及18100端口。该profile默认关闭，当前 compose.sh 不开启它；真实评测仍须独立授权，不得把旧 harness 暴露到公网。

```bash
curl --cacert .migration-artifacts/java-isolated-certs/server.crt https://localhost:18443/api/health
curl --cacert .migration-artifacts/java-isolated-certs/server.crt https://localhost:18443/api/ready
# /metrics 与 /actuator 明确404；仅/API/可被代理，SSE关闭缓冲。
```

health/ready 由集成平台实现，ready 必须验证 active release、物理节点与配置指纹；空库 health 可通过而 ready 应503。J5-03 必须检查实际 SSE 到达、断线恢复、证书、就绪负例、DB身份、容器权限及数据落盘，不能只检查页面200。

## 维护与备份

```bash
(cd backend-java && ./mvnw -DskipTests package)
scripts-java/operations.sh --help
# 必须显式提供 DPDK_JAVA_DB_* 身份环境；CLI不读取旧.env。
scripts-java/operations.sh purge-sessions --retention-days 30
scripts-java/operations.sh purge-sessions --retention-days 30 --apply
# Compose的PG不映射宿主端口；部署维护使用容器内同一CLI：
scripts-java/operations-compose.sh purge-sessions --retention-days 30
scripts-java/operations-compose.sh purge-sessions --retention-days 30 --apply
scripts-java/backup.sh "$PWD/.migration-artifacts/java-isolated-backup.dump"
scripts-java/backup.sh --apply "$PWD/.migration-artifacts/java-isolated-backup.dump"
```

CLI 强制关闭 web/启动迁移/后台恢复，全部写命令默认 dry-run；首次建库迁移由正常 Java 应用的身份守卫及唯一迁移入口执行。备份先校验四项数据库身份，custom dump 排除 `rag_task_inputs` 数据；恢复不得重新引入临时输入。保留 sessions/messages/citations/releases/jobs/usage/grades 等业务表。备份文件是敏感运行产物，只存在 `.migration-artifacts` 或用户指定受控位置，禁止入库。

恢复/回退由 J5-03 在另一个显式空库上执行：只读核对实例→创建独立目标库及身份标记→恢复→运行受控迁移→检查用户、会话、成绩、任务、旧引用、active release及临时输入为空→启动同构应用验业务。回退先判断新增数据兼容性，禁止以旧备份覆盖增量；本物料不自动执行恢复、删除卷或切换旧服务。

## 网络与资源

- `java-edge`：仅 frontend/backend；只有 frontend 绑定 loopback HTTPS。
- `java-data`：internal，postgres/backend；PG无宿主端口。
- `java-harness`：internal，backend/可选fake；harness专网无宿主端口。
- backend UID10001、代理显式非 root UID、fake UID1000；read_only、tmpfs、cap_drop ALL、no-new-privileges、CPU/RAM限制。PG需要自有数据卷写入，使用数据库镜像自身初始化流程。
- 不使用宿主 docker.sock、privileged、host 网络或父目录挂载。默认不构建/启动真实 harness。

## CI 与旧发布

`.github/workflows/java-isolated.yml` 分纯单测、显式专属 pgvector PG、前端 test/build、部署物料静态检查四个 job。默认不配置真实模型或真实 harness。unit job 的 PG 用例按现有环境门禁跳过，不能当 PG 通过；PG job 用显式 URL/user/data_directory 并经 TestPg 身份预检。CI不部署。

仓库检索未发现 `.github`、`.gitee.yml`、Jenkins 或 GitLab 的旧自动部署入口；Python部署脚本保留只读参考，没有新触发旧发布。新 workflow 只处理开发分支/PR/手动验证。
