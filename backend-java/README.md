# backend-java 运行与联调（U11 最小测试接线）

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
cd frontend && npm install && VITE_API_BASE=http://127.0.0.1:8090 npm run dev
```

CORS：`dpdk.cors-origins` 默认放行 vite **5174** 来源（SP-05：对齐冻结 Python 基线
FRONTEND_ORIGINS 与 frontend/vite.config.js 的 dev 端口 5174；`npm run dev` 原样
即可联调）；方法/头/credentials 与 Python CORSMiddleware 对齐（platform/web/CorsConfig）。
覆盖默认：`DPDK_JAVA_CORS_ORIGINS`（与 `dpdk.cors-origins` 同语义）。非允许来源
预检仍被拒绝（Spring 403，语义差异见 DECISIONS.md P2-D1）。

## 差分 oracle（对齐验证）

候选 trace 对齐入口见 `migration/contracts/P1_HANDOFF.md`（只读黄金，不反向改写）。
