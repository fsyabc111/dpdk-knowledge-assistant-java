# P2-REGRESSION 交接

- owner/工作树：P2 集成回归执行 Agent；worktree `.migration-artifacts/worktrees/p2-reg`（分支 `worker/p2-regression`，基线 9ba2378；本卡提交 ef5df4a → 73e88f9 → 8acb40e → d071e06 → 9115441 → 本提交）
- 源基线、目标提交或工作区摘要：验证型任务，backend-java 主代码零改动；唯一写入 `docs/java-migration/evidence/P2-regression/**` 与本交接
- 当前授权/契约/fixture版本：2026-10-25 U11 预授权 P2 集成回归；契约 p0 冻结面；fixture p0-v2（python-golden.json 186 observations）
- 状态：实现（验证执行）=完成；自动验证=完成（7 场景全过 + golden 契约 DIFF=0）；场景验收=本卡即场景验收证据，待集成者/主 Agent 复核；用户验收=未申请（随批次统一）

## 完成与文件

| 路径 | 改动目的 | 是否符合owner范围 |
|---|---|---|
| evidence/P2-regression/behavior-evidence.md | 场景1-7 行为证据摘要 | 是 |
| evidence/P2-regression/golden-diff.md | IP-3 golden 差分报告（归一化规则、分类统计、差异登记） | 是 |
| evidence/P2-regression/README.md | 证据目录说明+敏感扫描结论+未验证项+终态清理记录 | 是 |
| evidence/P2-regression/logs/（00-08 + golden-diff.json） | 启动迁移/各场景原始 curl 留证/golden 逐 case 明细 | 是 |
| evidence/P2-regression/scripts/（common.sh、fake_mcp_harness.py、run_01..07*.sh、run_08_golden.py） | 可复现验证脚本与 fake MCP 替身 | 是（scripts/ 为许可可写区） |
| docs/java-migration/handoffs/P2-REGRESSION.md | 本交接 | 是 |

未触碰 backend-java/、frontend/、contracts/、STATUS/MAIN/DECISIONS（worktree frontend/node_modules 临时符号链接已移除，git 零 diff）。

## 验证执行摘要

- **环境**：候选库从零重建（DROP/CREATE/COMMENT，data_directory=主仓库 .migration-artifacts/p1/pgdata）；首启 `path=EMPTY, applied=1, catalogVerified=true`；`AUTH_MODE=account` 重启 `path=HISTORY, applied=0, catalogVerified=true`；golden 对齐追加 `LLM_DAILY_QUOTA_DEFAULT=10000`。fake MCP harness（8100）提供 grade_plugin/list_protocols 等价替身，账本只记计数与源码 sha256 指纹。
- **场景1-7 全过**（原始留证见 logs/01–07）：认证（401 同体/WWW-Authenticate/登出吊销）；管理员（批量创建部分失败契约 created+skipped、停号 401、改密审批一次性令牌全链）；作业（201/可见性/撤回恢复）；正式提交（multipart→fake MCP→成绩；重复提交 best_score；**成绩行仅 code_hash、源码特征串 DB 扫描 0 命中**；停号不影响他人提交；**默认配额 20/天第 21 次精确 429**，失败计数保留）；CSV（utf-8-sig BOM/CRLF/中文表头/leader 视角未分配组+组间空行）；会话/用量（usage/cost 空账未知成本字段 null 非 0）；React（vite 200+挂载 div、CORS 预检放行头、带 Origin 登录 200、非允许来源 403 对照；vite 依赖经只读符号链接复用主仓库、cacheDir=/tmp，主仓库零写入）。
- **golden 对照（IP-3）：契约 DIFF=0**（PASS 78 / STATE-DIFF 6 / REPLAYED 15 / SKIP 87，逐条登记于 golden-diff.md 与 logs/golden-diff.json）。

## 决策与接口差异（登记，不阻塞）

1. **平台差异账本（live 确认）**：① secure-headers 中间件四头缺失（承 J2-01）；② 探针 `/api/health`、`/api/ready` 未迁移（Java=/actuator/health）；③ FastAPI 运行时路由 /docs、/openapi.json、/redoc、/metrics 404；④ CORS 非允许来源拒绝：Python 400 vs Spring 403（同为拒绝）；⑤ 无 Origin 普通 OPTIONS：Python 405 vs Java 404。
2. **范围外路由实测**：`POST /api/chat` 未装配（实测 404，chat 编排 J3+/J5）；`/api/rag/**` 未实施（J3）——golden 相应 case 登记 SKIP 并附实测状态。
3. **非 account 模式负例补充证据**：未设 AUTH_MODE 时全部业务端点整组 404（requireAccountMode 语义），已留证。
4. **verify 型环境对齐**：种子账号 daily_quota=10000、admin.created_by=leader、LLM_DAILY_QUOTA_DEFAULT=10000（对位 golden 引导状态）；fake harness list_protocols 冻结 golden 协议清单。均为替身数据，非主代码改动。
5. **首轮重放自伤修正**：golden DELETE 成功流曾以泛化路径删除等价会话（sess-p2reg-01），脚本已改为幂等重建+专用一次性会话（sess-p2reg-del），重放可重复执行。

## 验证证据

| 命令、工作目录及非秘密参数 | 构建/环境 | 退出码与断言 | 证据路径 |
|---|---|---|---|
| `bash scripts/run_01_auth.sh` … `run_06_usage.sh`（worktree 根） | JDK21+PG55481+fake harness 8100 | 各场景断言全过（摘要见 behavior-evidence.md 表） | logs/01–06 |
| `bash scripts/run_05_report.sh` + leader 导出补充（run_05 内 S5.4） | 同上 | BOM/CRLF/表头/未分配组验证通过 | logs/05-report.log |
| `bash scripts/run_07`（vite：`VITE_API_BASE=http://127.0.0.1:8090`，config=/tmp/p2reg/vite.p2reg.config.mjs） | 同上+vite 7.3.6 | 页面 200+挂载 div；预检 200+ACAO；带 Origin 登录 200；evil origin 403 | logs/07-react-cors.log |
| `python3 scripts/run_08_golden.py` | 同上（LLM_DAILY_QUOTA_DEFAULT=10000） | **契约 DIFF=0**；PASS 78/STATE-DIFF 6/REPLAYED 15/SKIP 87 | logs/08-golden-replay.log、golden-diff.json |
| 敏感扫描（grep token/bcrypt/口令/源码特征串） | — | 0 泄漏（详见 README） | evidence/P2-regression/README.md |

失败、跳过、未运行：无失败；SKIP 87 条均为登记性跳过（场景1-6 已留等价证据/SSE 与模型 live/J3 范围/chat 未装配/Python 平台面），无静默跳过。真实模型/真实 harness/浏览器点击全流程未执行（范围外，已声明）。

## 未完成、阻塞与回退

- 无阻塞。未完成项均为范围外登记（见上）。
- 回退：本卡为纯证据交付，revert 5 个提交即可；测试库已 DROP、进程已停，无外部副作用残留。
- 遗留给集成者/主 Agent 的最小建议：将平台差异账本第 1-5 条与 J2-01 登记合并维护；`POST /api/chat` 的 401/422 契约面待 chat 路由装配后（J5-01）按同一重放脚本补跑。

## 恢复点

- 未提交文件：无（git 状态干净）；仍运行进程/端口/容器：无（8090/5173/8100 已全部停止，p1_java_candidate 已 DROP WITH FORCE，pgdata 目录保留原位）。
- 锁定/释放的共享文件：无；worktree frontend/node_modules 符号链接已移除。
- 下一步的准确命令或动作：主 Agent 复核本卡证据后按 COORDINATION.md 合并 worker/p2-regression；如需复跑，按 evidence/P2-regression/README.md「复现」节（重建空库→起 fake harness→起应用→顺序执行 run_01..07→run_08_golden.py）。
- 主 Agent 需要决定的最小问题：无阻断决策；仅上述平台差异账本合并与 chat 路由补跑时点需要排期。
