# P0 阶段验收报告（执行中草稿）

日期：2026-10-24。当前实施/验证尚在进行，本文不构成 P0 用户验收或 P1 授权。

## 目标与身份

- 范围：J0-01 基线/契约，J0-02 合成 fixture/Python oracle/差分器及合成容量恢复基线；没有 Java 候选。
- 仓库：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`，origin `https://github.com/fsyabc111/dpdk-knowledge-assistant-java.git`。
- 起点：`80b29135faddd16d5e39fdbf0633291ea6a20489`；开发分支 `codex/p0-baseline-oracle`。本轮只允许本地提交，未推送/合并/部署。
- 功能来源：本仓库冻结 Python 副本；原源码 HEAD+dirty manifest 只用于来源校验，不访问原目录当前状态。
- 契约/fixture：p0-v1，尚待最终冻结；真实模型/embedding/评测未授权、未执行。

## 已获得的证据

- J0-01 初版：378 基线文件校验通过；66 method/path、29 表、103 配置、63 模型映射覆盖；清单校验为静态确认，不等于行为全部通过。
- Python3.11.15 独立 venv 安装锁定依赖及 dev 共133包，`uv pip check` 通过。首次下载因网络慢主动中断，最终使用锁文件原镜像和仅安装进程的 NO_PROXY 成功；未改系统代理或基线版本。
- 专用 PG17.10+vector0.8.4：本仓库 `.migration-artifacts/p0/pgdata`，127.0.0.1:55473，p0_oracle 角色，三个独立合成库。动态入口须验证 host/port/role/db/comment/data_directory。
- 性能资格（非正式容量验收）：60 登录、180 读作业、79 问答、2 正式提交及讲解，异常0；完整三轮测量待执行。
- 队列/恢复：20active/20waiting、41请求中的1立即429与20排队超时、槽释放/输入清理/配额断言已获得证据；SIGKILL恢复和重放不重复扣配额/调用 MCP 的替身场景已有证据，细节以 J0-02 独立报告为准。

## 关键待决与发现

- COMP-003：Python 对 submitted+unknown/running 会以同一 job 再调用grade_plugin。用户已明确接受 Java 对running仅等待/查询、unknown/查询失败停止自动重投的安全差异，详见 DECISIONS/P0-D13。Java尚未实施，不能把源行为复现当作Java安全策略已通过。
- 首次和重放的 title/score/next_steps 不同；会话删除后 rag_requests 可保留悬挂身份。oracle 按场景保留源事实，不修 Python、不删除关键关联断言。
- 合成阈值须完整测量后提出数值并由用户确认，尚不能宣布最终冻结。

## 实施与验收分级

| 维度 | 当前状态 |
|---|---|
| J0-01/J0-02 实现 | 执行中 |
| 自动验证 | 初版静态/资格部分完成，完整差分/三轮负载待收口 |
| 合成场景验收 | 部分取得证据，待独立审查 |
| Java 行为/性能 | 无候选，未执行 |
| 真实模型/embedding/harness | 未授权，未执行 |
| 用户验收 | 未进行 |
| P1 | 未授权，保持 planned |

## 回退与资源

撤回新增 migration/契约/fixture 版本即可回退本阶段；冻结 backend/前端/原环境未写入。新建 PG 与测试库/数据目录保留，阶段结束停止新进程但不删除恢复资产。服务与准确恢复命令以 handoffs/MAIN.md 为准。
