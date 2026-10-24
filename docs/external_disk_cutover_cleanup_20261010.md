# 外接部署 C/D：正式入口切换与内置盘清理

日期：2026-10-10（Asia/Shanghai）。用户明确授权 C、D 连续执行，确认正式组长 `U20260000001`，并确认清理旧实体、保留原路径链接。技术工作已完成；用户体验/问答质量验收不由本报告代替。

## 当前使用方式

- 网页：`https://localhost`，本机回环 80/443，自签证书沿用既有配置。
- 用户名：`U20260000001`，角色 leader。随机初始密码位于本机私密文件 `/Users/fsy/Documents/DPDK-Deployment-Backups/20261010-CD/admin/.env`，权限 0600；首次登录提示改密。密码不写聊天、日志或 Git。
- 部署和源码主位置：`/Volumes/study/dpdk-runtime`。原 `/Users/fsy/code/dpdk`、`/Users/fsy/code/teaching_harness`、`/Users/fsy/.colima` 均为外接目标的符号链接，不再有第二套原实例。
- Docker 默认 context 已切至 `colima-dpdk-ext`；Colima profile 为原 dpdk；4 CPU/4 GiB/30 GiB 数据盘，原镜像旧代码仍保持。
- 四服务 Running，backend/PG healthy；schema 为原后端支持的 **0013_task_recovery**。当前工作区 0015 代码没有部署到运行镜像。
- 宿主机 Homebrew PG 服务与自启已停止，其数据目录已清理；Colima/Docker CLI、Homebrew 和全局 Python 等小型工具保留。

日常入口（`~/.local/bin` 已在本机 PATH 中）：

```bash
dpdk-assistant start
dpdk-assistant status
dpdk-assistant backup
dpdk-assistant stop
```

`start` 先检查外接卷 UUID/权限、原镜像与 PG 容器身份及恢复记录，然后启动既有实例/服务。磁盘缺失时拒绝创建替代环境。`stop` 拒绝 pending 任务未终态的停机；停止完成后再正常弹出磁盘。

原 `start_dev.sh` / `stop_dev.sh` 在外接配置存在时也使用统一入口，不再启动宿主机旧数据库路径。本地 `local_harness.sh` 在该模式下拒绝启动旧栈/SSH 转发并指向统一入口。登录自启、定时备份和 APFS 加密没有自动配置。

## C 切换证据

1. 对宿主机所选 `assistant_dev` 冻结新写入，确认没有其他客户端连接。以 UTC 对 26 个业务表的**原有列**逐行摘要核对，源库和外接 0013 副本一致，没有发现数据分叉；不把 schema 新增列当数据丢失。
2. 宿主机全部 5 个库（assistant、assistant_dev、assistant_rag_probe_18334.tmp、assistant_rag_test、postgres）分别最终 pg_dump，排除临时执行输入数据。每份都在外接 PG 的独立 template0 空库实际还原、核对全表计数后清理演练库；角色配置另存无密码版 globals。备份保存在双盘，不混入正式服务库。
3. 通过原镜像内的 UserService 建立正式 leader 账号并设随机初始密码。HTTPS 登录、leader 管理权限、作业只读入口、退出后 token 失效通过。初始化辅助 SQL 曾将布尔列写成整数，之后使用既有 set_password 方法纠正；没有修改运行镜像。
4. 停止 Homebrew PostgreSQL 服务/登录自启。没有删除其可执行程序。核对没有宿主机 PG 进程后才允许清理数据目录。
5. 安装统一命令入口，原路径改成链接；原实例复制检查新增“有已核实退役记录的同目标链接”分支，避免链接后的 Running 状态被误判为另一份 VM。没有放开未知卷/错误链接/两份真实 VM 同时运行。
6. 默认 Docker context 切为外接；主仓库和 10ba/752b 两个已有 worktree 的 HEAD 可正常读取，Git 历史和未提交资料保留；无 commit/push/fetch/pull。

正式库保留：27 原成绩、74 原会话、148 原消息、26 原请求、395 引用、3737 向量节点；新增 1 个用户为本次正式 leader。原匿名/旧本地身份会话没有自动转给新账号，旧演练库仍单独保留为 `assistant_s4_archive_b20261010`。

## D 删除与保留

删除前，原目录先原子改名为明确的 retired 暂存路径，安装链接并核对运行/工作树；通过后才递归删除暂存实体，未对链接目标执行删除。

| 已清理实体 | 保留/恢复依据 |
| --- | --- |
| `/Users/fsy/.colima-retired-20261010-CD` | 原实例已在外接盘正常运行；应用镜像独立 save 包；PG 原容器 rootfs 恢复包和 SQL 备份 |
| `/Users/fsy/code/.dpdk-retired-20261010-CD` | 13233 个非缓存文件副本核对；独立源码/Git/未提交资料压缩包；全部历史证据另存 |
| `/Users/fsy/code/.teaching_harness-retired-20261010-CD` | 源码副本及独立压缩包，原运行镜像保持 |
| `/opt/homebrew/var/postgresql@17` | 全 5 库最终双盘逻辑备份与逐库实际恢复验证 |

可重建的本地 venv、node_modules、浏览器测试缓存和重复日志所在原实体随上述清理移除。全部历史 `.logs` 已迁到外接工作副本并另存独立压缩恢复包，1755 个历史文件副本核对。原 `.env`、旧环境配置和历史 SQLite/数据库归档单独保留在 `migration/20261010-CD/retained-local-settings`，不是作为缓存丢弃。

清理直接释放 **9.74 GiB**；内置盘最后可用约 **62 GiB**（批次开始约 54 GiB，期间新增独立备份约 1.5 GiB，所以净变化与直接清理量不同）。本部署目录占用约 9.3 GiB，外接卷还有其他用户目录，不在本次清理范围。

## 独立恢复包与 PG 的维护边界

内置独立恢复包目录：`/Users/fsy/Documents/DPDK-Deployment-Backups/20261010-CD/`，约 1.5 GiB，目录 0700。主要内容：

- 5 份宿主机最终 DB dump 和已还原验证清单；无密码 globals。
- `original-application-images.tar.gz`：原三个应用镜像及已有缓存 PG 镜像，包含镜像配置/层，归档已完整读取验证。[Docker image save](https://docs.docker.com/reference/cli/docker/image/save/)。
- `original-pg-container-rootfs.tar.gz` 与恢复配方：保留原 PG 容器代码文件系统，**不含 PG 数据卷**，配方只保留 PATH/LANG/PG_VERSION 等公共变量，不把容器中的 API key/数据库密码烘焙到镜像。[Docker container export](https://docs.docker.com/reference/cli/docker/container/export/)。
- PG rootfs 恢复包已在无网络、只读根目录、tmpfs 数据目录的临时容器中真实启动验证：PG17.11、vector0.8.6、zhparser2.3；验证容器/恢复镜像随后清理。没有替换原运行 PG 容器。
- 源码/Git/未提交资料包、全部历史证据包、原 Colima 基础镜像包、外接 `.env`/证书/运行配置/冻结 ID 和启动入口副本；各包带大小/SHA256/可读取验证记录。

PG 原镜像索引缺失的既有情况仍存在。当前启动只启动原 PG 容器，镜像/容器 ID 被冻结；不能直接手工 `compose down` 后自动重建。独立 rootfs 包提供灾难恢复路径，但导入恢复后的镜像 ID 会不同，需要在恢复时明确更新冻结记录，不能自动绕过检查。恢复旧机器/磁盘不再依赖已删除的原 VM 实体，而依赖这些独立恢复包和 SQL 归档。

当前运行库还会保存到 `/Volumes/study/dpdk-runtime/backups/`，同名独立副本在 `/Users/fsy/Documents/DPDK-Deployment-Backups/current/`；本轮含正式 leader 的最终备份也在空库实际还原验证，源码临时输入仍为 0。后续手工 backup 仅说明文件/目录与副本检查，实际还原状态见对应 JSON 的 restore_verified。

## 最终验证与边界

| 项目 | 本轮状态 |
| --- | --- |
| 当前工作区自动门禁 | 清理依赖前 836 passed / 93 deselected，cov 87.68%；ruff 通过；入口专项 23 项；shell 语法检查通过 |
| 原镜像代码回归 | B 批次 799 passed，不冒充本轮重复执行 |
| 删除前源数据核对 | 26 业务表原列逐行摘要一致；5 库备份逐库真实恢复 |
| 正式账号与权限 | 指定 leader 账号随机初始密码；HTTPS 登录/管理权限/退出失效通过；首次改密提示保留 |
| 清理后的整机停启 | 统一入口正常停 VM/服务并重新启动；backend/PG healthy，ready=200，正式账号仍能登录 |
| 最终运行库恢复 | 新备份在空库实际恢复；用户1、成绩27、会话74、消息148、请求26、引用395、节点3737、临时输入0 |
| Git 与路径 | 三个原路径链接正确；主仓库及两个已有 worktree 的 HEAD 可读；未提交变更/资料保留 |
| 运行代码 | 原镜像 ID/PG 原容器保持；无下载/软件升级/模型调用；schema 0013 |
| 真实教学质量/容量 | 未验证；本轮没有真实 LLM/embedding，不宣称旧代码已达到最新质量或新代码已发布 |
| 用户验收 | 待用户实际登录使用；没有把批次授权当作体验验收 |

证据位于外接工作副本 `.logs/external_disk_CD_20261010/evidence/` 和 `migration/20261010-CD/`；源文件摘要不含正文/密码。C/D 本批已收口，没有自动开启其他升级、索引重建或费用测试。
