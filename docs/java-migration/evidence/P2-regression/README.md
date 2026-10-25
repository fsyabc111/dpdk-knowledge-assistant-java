# P2 集成回归证据目录

owner：P2 集成回归执行 Agent；worktree `p2-reg`（分支 `worker/p2-regression`，基线 9ba2378）。
对象：真实运行的 Java 应用（spring-boot:run，8090）+ 独立测试 PG 55481/p1_java_candidate +
fake MCP harness（8100）。不修改 backend-java 主代码；本目录与 handoffs/P2-REGRESSION.md 为唯一交付。

## 内容

| 路径 | 说明 |
|---|---|
| behavior-evidence.md | 场景 1-7 行为证据摘要（认证/管理员/作业/正式提交与配额/CSV/会话用量/React CORS） |
| golden-diff.md | IP-3 golden 重放差分报告（契约 DIFF=0；STATE-DIFF/SKIP/平台差异逐条登记） |
| logs/00-startup.log | 启动与迁移路径证据（EMPTY/HISTORY、health UP、非 account 模式负例） |
| logs/01-auth.log … 07-react-cors.log | 各场景原始 curl 请求/状态码/响应体（token/bcrypt/口令统一打码） |
| logs/08-golden-replay.log | golden 逐 case 重放明细 |
| logs/golden-diff.json | golden 重放机器可读汇总（verdict 分类） |
| scripts/common.sh | 公共环境与打码/留证函数（req/reqsave/psqlq） |
| scripts/fake_mcp_harness.py | fake MCP Streamable HTTP 替身（grade_plugin/list_protocols；只记计数与源码 sha256 指纹，不落源码） |
| scripts/run_01_auth.sh … run_06_usage.sh | 场景脚本（可复现） |
| scripts/run_08_golden.py | golden 重放脚本（IP-3） |

## 敏感扫描结论

- 43 位 token、口令、reset_token 在日志中全部 `<TOKEN_MASKED>/<PW_MASKED>` 化（common.sh mask）。
- bcrypt 哈希字面量不出现在本目录（运行时以 BcryptGen 生成）。
- 数据库内源码特征串（synthetic/printf/stdio）扫描 0 命中；成绩行仅 code_hash。
- fake harness 账本只含 tool/protocol/长度/sha256 前 12 位。

## 未验证/范围外

- 真实模型、真实 teaching_harness 容器、真实 embedding 未调用（按门禁使用等价替身）。
- chat 主路由（POST /api/chat）与 /api/rag/** 未在当前构建装配（J3+/J5 范围），golden 相应 case 已登记 SKIP。
- 浏览器端到端点击流程不在本卡（U11 为最小接线：页面 200+CORS 预检+带 Origin 登录）。
- 终态清理：应用/vite/fake harness 已停止，p1_java_candidate 已 DROP（2026-10-25），无残留进程/库。
