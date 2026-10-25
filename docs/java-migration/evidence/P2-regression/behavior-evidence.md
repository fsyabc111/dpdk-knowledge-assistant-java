# P2 集成回归行为证据（真实运行应用，非 MockMvc）

环境：worktree `p2-reg`（分支 worker/p2-regression，基线 9ba2378）；应用 Spring Boot 8090
（`AUTH_MODE=account`，golden 对齐追加 `LLM_DAILY_QUOTA_DEFAULT=10000`）；独立测试 PG
127.0.0.1:55481/p1_java_candidate（data_directory=.migration-artifacts/p1/pgdata，身份预检通过）；
fake MCP harness 127.0.0.1:8100/mcp（`scripts/fake_mcp_harness.py`，grade_plugin/list_protocols
等价替身，只记计数与源码指纹，不落不打印源码）。

## 启动与迁移（logs/00-startup.log）

| 项 | 结果 |
|---|---|
| 空库从零迁移 | `启动迁移完成: path=EMPTY, applied=1, catalogVerified=true`，/actuator/health UP |
| 二次启动（HISTORY 路径） | `path=HISTORY, applied=0, catalogVerified=true`，UP |
| 非 account 模式负例 | 未设 AUTH_MODE 时全部业务端点 404（整组不泄露），符合 requireAccountMode 语义 |

## 场景结果（原始 curl 请求/状态码/响应体见 logs/01–07）

| 场景 | 关键断言 | 结果 |
|---|---|---|
| 1 认证 | 登录 200（token 43 位 base64url、expires_at、must_change_password）；错误密码 401 与不存在用户**同体同长**；无凭据 401+`WWW-Authenticate: Bearer`；登出后旧 token 401 | 通过 |
| 2 管理员 | 批量创建 1 新+1 重复 → 200 `{"created":["U90000000006"],"skipped":["U90000000003"]}`；停号 204→登录 401 同体→恢复可登录；改密审批流：申请 202 空体→列表→审批返回一次性 reset_token（43 位、TTL 30min）→重置 204→旧密 401/新密 200（must_change_password 翻 false）→**旧 bearer token 401**→令牌重放 400 | 通过 |
| 3 作业 | 创建 201（2 协议项 dns/arp，deadline 响应为 Python str 形态 `2099-01-01 00:00:00+00:00`）；学生列表可见；PATCH 截止生效；撤回→学生列表消失→reactivate 恢复 | 通过 |
| 4 正式提交 | multipart .c 提交 → fake MCP 评测 → 200 成绩（score/passed/total/points/earned/best_earned/submission_id）；重复提交后作业详情 best_score/earned/submission_count 正确（80/100*20=16.0）；管理端 grading/submissions 只读行含 hash；**SQL 验证：grading_submissions 仅 code_hash，源码特征串 0 命中**，results_json 含 accepted_assignment 快照；停号学生03→其 token 401，学生04 提交不受影响；配额默认 20/天→**第 21 次精确 429** `今日评测配额已用完（20 次/天）`，usage_counters(kind=grading)=22（失败计数保留语义） | 通过 |
| 5 成绩/CSV | 汇总行 scores/total_earned/submission_count 正确；导出 CSV：`text/csv;charset=utf-8-sig`+`Content-Disposition attachment`，**BOM(utf-8-sig)=有、CRLF=有**、中文表头 `班级(老师学号),学生学号,arp(满分30),dns(满分20),总分,提交次数,最近提交时间`；leader 视角出现 `未分配` 组（created_by=NULL 学生）+组间空行；学生角色访问成绩端点 404 不泄露 | 通过 |
| 6 会话/用量 | 无公开建会话端点（与源一致）→SQL 造数：列表/详情/删除 204/删除后 404；usage/me today+recent_days 7 天；usage/subjects 空账 `last_active_day:null`；usage/cost 空账 `prompt_tokens/completion_tokens/total_tokens/duration_ms = null`（未知成本 null 非 0）、`cost_micros_by_currency:{}` | 通过 |
| 7 React/CORS | vite dev server（只读符号链接复用主仓库依赖，cacheDir=/tmp）页面 200+`<div id="root">`；预检 OPTIONS /api/auth/login（Origin localhost:5173）→ 200 + `Access-Control-Allow-Origin/Methods/Headers/Credentials/Max-Age`；带 Origin 真实登录 200+ACAO；非允许来源 403 对照 | 通过 |

## golden 对照（IP-3）

见 `golden-diff.md`：**契约 DIFF=0**（PASS 78 / STATE-DIFF 6 登记 / REPLAYED 15 登记 / SKIP 87 登记）。

## 敏感纪律

- token/bcrypt/口令在日志中统一打码（scripts/common.sh `mask()`）。
- fake MCP harness 账本仅记录 tool/protocol/plugin_source 长度与 sha256 前 12 位（见
  /tmp/p2reg/harness_state.json 结构），未落任何源码文本；数据库源码特征串扫描 0 命中。
- 种子口令=学号（合成账号），仅存在于独立测试库。

## 复现

```bash
# 1) 重建空库（psql 连 127.0.0.1:55481，见 common.sh）
# 2) python3 scripts/fake_mcp_harness.py --port 8100
# 3) AUTH_MODE=account DPDK_JAVA_DB_* ./backend-java/mvnw -f backend-java/pom.xml -q \
#      spring-boot:run -Dspring-boot.run.arguments="--server.port=8090 --dpdk.harness.url=http://127.0.0.1:8100/mcp"
# 4) 依次 bash scripts/run_01..07*.sh；python3 scripts/run_08_golden.py
```
