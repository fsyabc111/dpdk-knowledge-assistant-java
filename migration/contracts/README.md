# P0 冻结契约（p0-v1；当前fixture p0-v2）

状态：**源码、契约、fixture与合成门槛已最终冻结，自动验证与独立审查通过；等待P0用户阶段验收，P1未授权。**

源码功能目标是新仓库冻结提交 `80b29135faddd16d5e39fdbf0633291ea6a20489`，其复制内容为原 `a47a1bc9d23c0a8e625a79ff7cfbd096dcb3071c` 加复制时 dirty/untracked 文件。逐文件以原 manifest 的 `copied_path` 为准，三项有意映射见 COPY_REPORT；不访问旧源码目录。旧部署镜像/schema0013 不是本次功能目标。

## 机器接口与稳定字段

- `migration/baseline/static-inventory.json`：JSON object，`version=p0-v1`。稳定集合：`routes[]`（主键 `id = METHOD + 空格 + path_template`）、`production_tables[]`（主键 `name`）、`settings[]`（主键 `name`）、`models[]`（主键 `id = source_path::ClassName`）、`alembic_revisions[]`、`ddl_operations[]`、`dependencies`、`manifest`。
- `migration/contracts/coverage-map.json`：同版 JSON object；`routes[]/tables[]/settings[]/models[]` 的主键分别对应上项。每项恰有一个 `owner_task` 与 `java_target`；数据表另有唯一 `migration_owner_task=J1-01`。初版补充语义会保留主键和字段类型；破坏性调整须升级版本并通知消费者。
- 所有源码位置均为仓库相对 `path`、1-based `line/end_line`。`models[].bases/fields` 保留原基类表达式和**自有字段**，字段格式保持不变。新增 `resolved_bases` 解析导入别名/跨模块/re-export；`inheritance_mro` 给出本地C3顺序（外部类保持不透明）；`inherited_fields/effective_fields` 分别为未被覆盖的继承字段/最终字段，每项含 `declared_in`、`inherited` 和原声明 `path/line/end_line`；`field_model_references` 为可静态解析的模型类型引用。覆盖校验同时核对这些定义与引用，不能只比模型个数。`routes[].parameters` 是签名表达式，非 OpenAPI 生成物；Request 手工解析、多态字典返回须按源代码/fixture 补完，不能据此假装自动 DTO 已验证。
- 校验：`python3 migration/baseline/inventory.py`。`--write` 仅重建源码枚举，不覆盖人工契约映射。报告：`docs/java-migration/evidence/J0-01/coverage-report.json`。当前 66 项 HTTP method/path（57 项装饰器、1 项 metrics 动态注册、8 项文档 GET/HEAD）、29 项生产/元数据表、103 项 Settings、**64 项 Pydantic 模型**均已单 owner 映射；精确数量以报告为准。初版63项的完整性声明被独立审查S-02/S2纠正：漏掉了 `ManagerAssignment(AssignmentInfo)`；当前修复保留2个自有字段、10个可追溯继承字段、12个最终字段。初版记录留在 `docs/java-migration/evidence/J0-01/coverage-report-initial-reviewed.json`，不把旧63项结果继续当作全量证明。

## 路由分类

静态 AST 覆盖全部 include_router、API 装饰器、隐藏 ready/维护端点及 `Instrumentator.expose('/metrics')`。FastAPI 构造器默认注册 `/openapi.json`、`/docs`、`/redoc`、`/docs/oauth2-redirect` 的 GET/HEAD，已单独标记 `registration_kind=FastAPI.default`。未识别动态 add_route/mount/expose 将使覆盖校验失败。

业务 APIRoute 不自动添加 HEAD；未声明方法按框架方法不允许处理。CORS preflight OPTIONS 是 CORSMiddleware 对带 Origin/Access-Control-Request-Method 请求的拦截，**不是额外业务路由**；允许来源取 FRONTEND_ORIGINS，方法仅 GET/POST/PATCH/DELETE，允许头 Content-Type/Authorization/X-Maintenance-Key、credentials=true（`backend/app/main.py:27`）。普通 OPTIONS 不被当作 preflight。J0-02 必须实测并固定成功/失败 preflight、业务 HEAD 的状态和 Allow/CORS 头；文档框架路由属于应用内部兼容面，未来是否关闭须批准差异。

## P0 隔离规则（强制）

1. 禁止导入 app 做枚举；inventory 只用 Python AST/文本与哈希。不加载 `backend/.env`、shell ambient provider 配置或默认数据库。任何动态 oracle 应在显式替身/测试设置完成后再导入应用。
2. 唯一当前专用资源由主 Agent 建立：PostgreSQL 17.10 + vector 0.8.4，`127.0.0.1:55473`，角色 `p0_oracle`，库 `p0_python_oracle` / `p0_candidate_oracle`（J0-02）；额外性能库 `p0_benchmark_oracle` 仅性能 owner 使用；database comment 必须精确等于 `java-migration-p0-synthetic-80b29135`。`SHOW data_directory` 解析 realpath 后须精确为本仓库 `.migration-artifacts/p0/pgdata`。启动动态测试先检查 host/port/role/database/comment/data_directory，任一不符立即失败。禁止回退旧 `.env`、宿主机默认 PG、旧部署或业务库。
3. 本地依赖环境 `.migration-artifacts/p0/venv`；PG 数据只在 `.migration-artifacts/p0/pgdata`。本卡不启动/停止/清理这些共享资源，资源归主 Agent。不能执行复制来的 start_dev/local_harness/deploy/Compose 入口。
4. 模型、embedding、MCP、评测只允许本地合成替身或死上游。拒绝真实 provider 出网；假 key 不代表已隔离。不得读取或复制真实患者/学生数据、源码执行输入、模型原始输出或旧日志作为 fixture。
5. harness 提交后超时/断线视为结果未知，P0 替身必须覆盖这一情况；不盲重试提交。受理事务不能跨越模型/MCP 调用。恢复不等同于多副本 fencing；正式 submit 没有业务幂等键。
6. 可提交 evidence 只保留合成输入、脱敏摘要与断言。原始命令输出/测试日志存 `.migration-artifacts/p0/`，禁止提交运行产物或凭据。

当前消费fixture为p0-v2，历史p0-v1保持不变；容量/恢复/向量阈值由用户确认并固定在thresholds.json（U09/P0-D15），真实质量仍为live_not_authorized。P1消费顺序与限制见P1_HANDOFF.md；最终证据见docs/java-migration/evidence/P0/phase-report.md。

## 审查与 P1 消费入口

| 文件 | 内容 |
|---|---|
| `identity-and-data.md`、`identity-vectors.json` | NFC/Python strip、UTF-8原件/执行源双摘要、UUID5、token/bcrypt、配置串、时间/空值/round；向量是纯标准库参考，待oracle交叉核对 |
| `http-and-sse.md`、`function-catalog.json` | HTTP错误、multipart、CSV、SSE、helper错误源目录；首次/重放各自比对 |
| `transaction-ports.json` | P1直接消费的18个模块端口、参与事务/所有权、数据字段与源码位置；都是拟议Java签名，未实施 |
| `schema-migration.md` | 全量生产schema来源、空库/合成0013/等价0015路径、完整catalog预检和唯一J1-01迁移owner建议；尚未登记baseline |
| `external-dependencies.json` | 19项直接Python需求、17项非标准库import根、11项外部系统、4项YAML配置键；全部外部可用性未验证 |
| `compatibility-differences.json` | COMP-001..010；无fencing、正式submit无业务幂等、恢复业务重投待harness证据/批准、first/replay元数据差异等 |
| `thresholds.json` | 确定性100%/未解释差异0，合成容量/恢复与固定向量数值待J0-02；真实质量未授权 |

`coverage-report.json` 还检查96项 `.env.example` 键均有 Settings去向、4项知识YAML键均有去向、依赖需求/import映射无遗漏。`python3 -m unittest discover -s migration/baseline -p 'test_*.py' -v` 用合成AST/映射突变检查漏项、重复owner、未知动态注册、隐藏/框架面分类、别名/跨模块/多层/菱形继承及真实ManagerAssignment。当前10项护栏通过，原初版为5项；脚本在系统Python3.9与专用Python3.11.15均验证，不需要安装/导入应用依赖。

本次交付为 `review-ready / final-freeze-awaits-J0-02`。没有Java实现、部署/外部探测或阶段放行；主Agent收齐oracle、合成性能/恢复测量与独立审查后，再完成最终阈值/差异冻结。
