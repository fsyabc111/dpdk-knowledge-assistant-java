# 外接盘迁移 A 批次：源数据与部署准备

日期：2026-10-10。用户授权范围：A 批次；完整 Docker Compose 部署。
用户已明确选择宿主机 `assistant_dev` 作为新部署主库。正式库名仍为容器内 `assistant`，通过还原映射，不合并其他库。

后续状态：用户已授权并实施 B，并选择复用原实例/镜像/旧代码，不再按下面的原始交接去新建/构建/升级0015。当前实例、入口与恢复版本见 [B 记录](external_disk_batch_b_20261010.md)；下面的「B未授权」等行保留 A 收口时点。

## 实现结果

- 外接盘 UUID `849D1FBD-CF92-4B50-9655-6D5E2F9C2A0B`，挂载 `/Volumes/study`；已启用属主管理。卷根目录归 root 所有，部署子目录 `/Volumes/study/dpdk-runtime` 已核实归 fsy、权限 0700。APFS 加密和登录自启未配置。
- 已复制 DPDK、助手、teaching_harness 三个仓库，保留 Git 历史、未提交文件和用户资料。宿主机 `.venv`、node_modules、dist、`.runtime`、`.logs`、旧备份目录和可重建缓存不进入运行副本；原文件保留。
- 外接副本的 `deploy/.env` 设定主仓库/harness 路径、容器内部数据库/harness 地址、account 模式；内置盘原 `.env` 和证书未修改。实际外接配置 `deploy/external_disk.json` 已忽略 Git/镜像构建。
- 新增 `deploy/external_disk.py` 与 `docker-compose.external.yml`：校验卷身份、权限、空间和目录；固定新实例及 Compose 项目；替换成回环 80/443；隔离镜像名称并限制日志轮转；提供启动、停止、状态和备份入口。没有执行新 VM 创建、镜像构建、数据库恢复或新服务启动。
- 本轮工作分支 `codex/external-disk-a`，源代码基线 `a47a1bc9d23c0a8e625a79ff7cfbd096dcb3071c`；无 commit/push/fetch/pull。

## 源数据库核查与备份

| 来源 | schema/关键内容 | 处理 |
| --- | --- | --- |
| 宿主机 assistant_dev | PG17.10、vector 0.8.4；0011；27 成绩、74 会话、148 消息、26 RAG 请求；0 账号/作业；活动索引声明/成员/物理均 3737 | **用户选定主库**；B 批次还原后升级到当前 head；账号初始化和匿名历史归属另按既有规则处理 |
| 宿主机旧 assistant | 1477 会话、3057 消息、3583 物理向量节点；无新版账号/成绩/发布表 | 单独归档，不合并进主库 |
| 旧 Compose assistant | PG17.11、vector 0.8.6、zhparser 2.3；0013；62 账号、2 作业、18 成绩、84 会话、86 RAG 请求；活动索引声明/成员/物理均 43 | 与 2026-09-22 部署演练记录一致，保留独立归档，不作为新主库 |

旧 Compose 核查时：请求全部终态，`rag_task_inputs=0`，当日用量账本新增行数 0。旧 VM 启动会按策略短暂恢复容器，本轮随即停止应用服务，只保留 PG 读取备份；核查结束后旧 VM 已恢复 Stopped，Docker 默认 context 仍为 default。Docker 容器已由本轮显式停止，未来恢复旧环境需主动启动所需服务，不应假定只启动 VM 就会自动恢复应用。

所有备份均排除 `public.rag_task_inputs` 数据。归档目录检查确认无该表 TABLE DATA 条目；备份恢复实操属于 B 批次，**本轮未还原**。

| 文件名 | 字节数 | SHA256 |
| --- | ---: | --- |
| host-assistant-dev.dump | 20034435 | a28d748b6c93a2736d4878b8d75c3d7e5fb93c7a666ac0db93d61120220152e9 |
| host-assistant-legacy.dump | 40319511 | a1fec137ae5fb28461a6bff8b9c5e13d4956af927edcd78ffecac81a9eadf2a9 |
| old-compose-assistant.dump | 456599 | 3603c0a70390c2be46cce13cc1551eaf06c66ebeb33479569be11cfb24a2528f |

保存位置：

- 外接备份：`/Volumes/study/dpdk-runtime/backups/20261010-A/`。
- 内置独立副本：`/Users/fsy/Documents/DPDK-Deployment-Backups/20261010-A/`。
- 外接清单/核查证据：`/Volumes/study/dpdk-runtime/migration/20261010-A/`。
- 本轮内部日志与源归档：原仓库 `.logs/external_disk_A_20261010/`，Git 忽略、受限权限；日志只记元数据，backups 子目录另存数据库归档。

两处备份逐份 SHA256 核对一致，目录权限 0700、归档 0600。源库后续仍可能变化，C 批次必须停写后重做最终备份，不能直接把 A 批次快照当最终切换数据。

## 验证状态

自动验证和场景证据将在收口时填写；以下检查不代表产品质量或用户验收。

| 项目 | 状态 |
| --- | --- |
| 部署拒绝路径测试 | 15 passed：错误 UUID/未挂载/属主管理关闭/低空间/符号链接越界/凭据权限/错误 socket/缺恢复记录/跨数据源记录/旧终端变量/误连宿主机库/pending 停机/备份临时输入排除/独立副本 |
| 后端默认集与覆盖率 | 828 passed / 93 deselected，覆盖率 87.68%（门槛 60%） |
| ruff | 后端全量及新增部署入口通过 |
| 前端 vitest/build | 102 passed；build 通过 |
| 真实外接卷与配置 | check、compose-config 已通过；未创建 VM |
| 项目副本内容校验 | 13223 个普通文件逐个 SHA256、6 个文件符号链接核对；无遗漏/未授权内容差异。唯一预期差异为外接副本 deploy/.env；完整清单存于受限证据目录 |
| 真实恢复、迁移链、容器/模型/harness 场景 | 未验证，属于 B 批次 |
| 用户验收 | 待验收 |
| B 批次授权 | 未授权，不自动启动 |

## 使用入口与 B 批次交接

从**外接工作副本**执行；不要在此副本运行 `start_dev.sh` 或 `local_harness.sh` 来混用宿主机旧库。

```bash
cd /Volumes/study/dpdk-runtime/workspace/dpdk/ai_chat_assistant
python3 deploy/external_disk.py check
python3 deploy/external_disk.py compose-config
```

A 批次只执行上述检查。后续 B 经授权后才依次使用 `vm-start`、`build`、`postgres-up`；它们不自动还原或迁移数据库。B 要先在隔离库验证备份恢复、扩展/指纹/迁移链，最终准备目标库后才能启动 backend。

`up` 要求 `/Volumes/study/dpdk-runtime/migration/restored-and-verified.json` 的 phase、UUID、context、Compose 项目、用户选定源、release ID 和 schema 共同匹配；本轮刻意不创建该记录。它是防误操作的记录，不代替真实恢复证据。B 真实验证后记录形状：

```json
{
  "phase": "restored-and-verified",
  "volume_uuid": "849D1FBD-CF92-4B50-9655-6D5E2F9C2A0B",
  "compose_project": "dpdk-assistant-ext",
  "context": "colima-dpdk-ext",
  "source": "host-assistant-dev",
  "release_id": "ccbb92e558534cbcbd5e3d3b2ee4a30d",
  "alembic": "0015_explain_purpose"
}
```

B 必须核验目标 pgvector 可兼容源库 0.8.4、真正空的 template0 恢复库与现有 zhparser initdb 配置无冲突；还原后 `init_db → alembic upgrade head`，不能 stamp。保持模型/embedding 指纹，不自动 ingest。首个管理账号尚未创建；真实模型测试预算尚未授权。

运行后 `backup` 会排除临时输入、验证归档目录并保存独立盘副本；不自动轮删旧归档，也未设置定时任务。`stop` 在 PG 可查询时拒绝仍有 pending 任务的停机；操作前须停止新提交、等待终态或通过既有 API 明确取消。服务/Colima 停止后再正常弹出磁盘，不以运行中拔盘测试拒绝逻辑。

## 回退

新环境尚未受理业务，原宿主机 PG/原配置仍保留，外接新实例尚未创建。回退只需继续使用内置原仓库；旧 Compose 如需恢复，明确指定旧实例与原项目启动服务，核对其 43 节点演练索引，不能把它误认成用户选定的主库。

外接盘属主管理已启用，卷根归 root；日常写入使用 fsy 所有的 `dpdk-runtime` 子目录。未删除任何旧 VM、数据卷、数据库或用户资料，未安排自动清理。
