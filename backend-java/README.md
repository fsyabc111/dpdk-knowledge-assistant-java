# backend-java 构建、运行与联调

## 构建与全量测试

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./backend-java/mvnw -f backend-java/pom.xml -B verify
```

PG 集成测试需要显式环境（缺省显式跳过，绝不回退其他库）：

```sh
export DPDK_TEST_PG_URL='jdbc:postgresql://127.0.0.1:55481/postgres' \
       DPDK_TEST_PG_USER=p1_java \
       DPDK_TEST_PG_PASSWORD='<见 .migration-artifacts/p1 实例口令>' \
       DPDK_TEST_PG_DATA_DIR="$PWD/.migration-artifacts/p1/pgdata"
```

实例（主Agent维护）：PG 17.10+pgvector 0.8.4，127.0.0.1:55481，
data_directory=.migration-artifacts/p1/pgdata，角色 p1_java（CREATEDB/SUPERUSER，合成实例）。

## 应用启动（独立测试库）

```sh
export DPDK_JAVA_DB_URL='jdbc:postgresql://127.0.0.1:55481/p1_java_candidate' \
       DPDK_JAVA_DB_USER=p1_java DPDK_JAVA_DB_PASSWORD='<口令>' \
       DPDK_JAVA_DB_NAME=p1_java_candidate \
       DPDK_JAVA_DB_COMMENT='java-migration-p1-test' \
       DPDK_JAVA_DB_DATA_DIR="$PWD/.migration-artifacts/p1/pgdata"
./backend-java/mvnw -f backend-java/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=8090
```

启动序列：fail-closed 配置校验 → 数据库身份预检（库名/角色/comment/data_directory）→
三路径受控迁移 → 对外服务。`dpdk.migration.run-on-startup=false` 可关闭启动迁移。

## React 联调（P2）

```sh
# 终端1：如上启动 Java（8090）
# 终端2：
cd frontend && npm ci && VITE_API_BASE=http://127.0.0.1:8090 npm run dev
```

CORS：`dpdk.cors-origins` 默认放行 vite **5174** 来源（SP-05：对齐冻结 Python 基线
FRONTEND_ORIGINS 与 frontend/vite.config.js 的 dev 端口 5174；`npm run dev` 原样
即可联调）；方法/头/credentials 与 Python CORSMiddleware 对齐（platform/web/CorsConfig）。
覆盖默认：`DPDK_JAVA_CORS_ORIGINS`（与 `dpdk.cors-origins` 同语义）。非允许来源
预检按冻结Starlette协议返回400及Disallowed CORS正文；成功预检200/OK与白名单头，P2-D1已在P5修复。

## 差分 oracle（对齐验证）

候选 trace 对齐入口见 `migration/contracts/P1_HANDOFF.md`（只读黄金，不反向改写）。

## P5隔离验收入口

完整候选采集见 `acceptance/contract/`，多角色浏览器资源生命周期见 `acceptance/ui/session.py`；均通过四项测试PG配置创建带本轮owner/comment的独立动态库，不借用旧库。所有输出放全新gitignored目录，禁止用Java结果覆盖冻结黄金。正式容量、故障、部署分别见根README链接。

数据库池可通过 `dpdk.datasource.maximum-pool-size` 或 `DPDK_JAVA_DB_MAX_POOL_SIZE` 显式设置，默认8、必须为正数；冻结容量比较使用32并记录实际配置。默认chat active20/waiting20/queue10s，业务600s墙和24h输入TTL不能用短测替代验收。

## 首次接手的独立PG资产

上文p1_java/55481/pgdata是本批次既有实例记录，不能作为新维护人默认数据库。首次完整验证前，环境owner须交付一个仅用于合成测试的PostgreSQL17.10实例，安装pgvector0.8.4并能CREATE EXTENSION vector，监听明确loopback端口、独立data_directory，专属角色有建删测试库与扩展权限；原业务库或旧.env不能借用。四项DPDK_TEST_PG_*逐项与该实例只读查询current_user/inet_server_port/current_setting('data_directory')一致后，TestPg才创建动态库并核验精确comment。口令经本地600文件或环境注入，不放shell历史/日志；缺该资产即报告PG未执行，不能把skip当完整验证。

已有本轮资产的接手者应由owner恢复同一实例，禁止重新initdb覆盖目录；全新机器需要owner先按其平台PostgreSQL17安装/pgvector扩展流程建立全新目录和角色，记录版本与身份再移交。本仓库不自动搜索本机库或安装/启动未知PG。纯Java验证与完整PG验证分别记录。上文原生应用例子的p1_java_candidate也必须先由owner在此实例创建并写明库comment；例子不是自动创建库命令。隔离Docker部署路径由runbook自己创建新project/卷/owned库，不依赖这个native测试实例。
