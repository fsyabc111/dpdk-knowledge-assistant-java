# 外接盘迁移 B 批次：复用原实例与镜像

日期：2026-10-10（Asia/Shanghai）。用户授权 B，并在执行中决定：不下载镜像，复用已有实例；进一步明确完全保持原镜像和旧代码，仅迁移存储位置。本批没有正式切换旧入口、停用宿主机 PG、删除原 VM、commit/push 或调用真实模型。

后续更新：用户已授权并完成 C/D，原实体已清理、原路径链接保留、宿主机PG停用、正式leader建立；下面保留 B 时点。当前入口和恢复方式见 [C/D 记录](external_disk_cutover_cleanup_20261010.md)。

## 最终部署身份

| 项目 | 最终值 |
| --- | --- |
| 外接卷 | `/Volumes/study`；APFS；UUID `849D1FBD-CF92-4B50-9655-6D5E2F9C2A0B`；属主管理启用；未加密 |
| 运行根目录 | `/Volumes/study/dpdk-runtime`，约 8.9 GiB 实际占用 |
| Colima 存储 | `/Volumes/study/dpdk-runtime/colima`，约 7.9 GiB 实际占用 |
| 实例 | 原 `dpdk` 的停机副本；VZ/ARM64、4 CPU、4 GiB RAM、30 GiB 数据盘；资源参数保持原值 |
| Docker context | 使用入口固定为 `colima-dpdk-ext`；socket `/Volumes/study/dpdk-runtime/colima/dpdk/docker.sock` |
| Compose 项目 | `dpdk-assistant`，复用原服务身份，受目标 VM/context 和原实例停止检查约束 |
| 主库 | 容器 `dpdk-assistant-postgres-1` 中 `assistant`；来源为用户选定的宿主机 `assistant_dev` |
| schema | **0013_task_recovery**，仅迁到原镜像支持的 head；没有升到当前源码的 0015 |
| 发布索引 | `ccbb92e558534cbcbd5e3d3b2ee4a30d`；声明/成员/物理均 3737，指纹匹配 |
| 访问 | `https://localhost`，只绑定回环 80/443；证书为既有本地自签证书 |
| 最终服务状态 | 四服务 Running；backend/PG healthy；内置原 Colima Stopped；宿主机 PG 保留运行；默认 Docker context 仍为 default |

这次运行的是原镜像的旧代码，不是当前 `a47a1bc` 工作区的源码发布。没有把其他缓存的 L4/L5 镜像替换进去：它们也与当前源码不同，并带有本批未授权的后续改动。

## 停机复制与镜像复用证据

原实例确认停止后复制整个 `.colima`。默认文件复制在一个逻辑 100 GiB、实际分配 0 字节的闲置磁盘上扫描耗时，改用 `SEEK_DATA/SEEK_HOLE` 数据区复制。每个区段带 offset/length/SHA256，写入后回读校验；保留逻辑尺寸，未把洞区写满。23 个非磁盘普通文件另核对 SHA256，无遗漏或差异。

- 系统盘逻辑 20 GiB，源数据区约 1.54 GB；活动数据盘逻辑 30 GiB，源数据区约 6.64 GB。
- 原 `_disks/colima/datadisk` 的逻辑 100 GiB 空盘也保留为稀疏文件，实际没有复制数据区。
- 复制与校验证据：`migration/relocated-vm.json` 和本批受限证据目录。启动后文件系统/运行时会正常写盘，不能把后续文件 hash 当停机复制时的 hash。
- 启动前校验原实例状态必须为 Stopped；不确定状态也拒绝启动副本。CLI 固定外接卷 UUID、目录和 context/socket，不允许默默改用内置盘。
- 基础镜像包复用原本机文件，SHA512 匹配原 Colima manifest，包副本位于外接缓存。初始方案曾试建空 `dpdk-ext` 实例，用户改方案后检查其中没有容器/镜像/数据卷，将这份空试建实例清理；原实例未删除。
- 用户改方案后没有下载/构建容器镜像。三个应用镜像只增加本地别名，ID 与原容器一致；原 PostgreSQL 容器直接保留。
- `migration/preserved-images.json` 固定三个应用镜像 ID 及 PG 原容器 ID/镜像标记；入口启动前逐项核对，标签漂移或 PG 被重建会拒绝执行。

| 服务 | 原/现运行镜像 ID |
| --- | --- |
| backend | `sha256:e9449e285464f8ceaf8fedfcca236c9c5e3924d1ccd0a60727fb1679ab8ba7c2` |
| frontend | `sha256:5b8e9ef356333043dd740a80585490fc6addcee3fb29e80884eae91398f3171e` |
| harness | `sha256:9e279f88cdc6c2cf0451af20fab68ce9369cec5c6c5dac563e0a87987d793986` |
| postgres（保留原容器） | `sha256:ab4129640061540fbb8daa89edfeb8b281da1f57cbee695ac9c0d2e84c5b447d` |

**PG 的既有维护限制**：旧容器的原镜像 ID 在镜像索引里已不可查，但容器 rootfs 完整且可重启。当前入口对 PG 只执行 `compose start postgres`，不重新创建它；其他三服务 `up --no-build --pull never --no-deps`。现有缓存 PG 镜像的别名仅用于 Compose 配置解析，不是当前运行 PG 的镜像 ID。后续如要重建 PG，应单独确认使用哪个镜像并演练恢复，不能手工 `compose down` 后认为可以原样 `up`。

## 主库恢复与原数据保护

1. 再核对 A 主库备份 `host-assistant-dev.dump` 的 20034435 字节与 SHA256 `a28d748b6c93a2736d4878b8d75c3d7e5fb93c7a666ac0db93d61120220152e9`。
2. 在外接 PG 的真正空 template0 库 `b_restore_probe`，用 `pg_restore --exit-on-error --single-transaction --no-owner --no-acl` 还原，不在已有 zhparser 初始化对象的库上覆盖还原。[PostgreSQL pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)。
3. 27 个源表行数与 A 清单一致；除版本表外的 26 个业务表以 UTC 逐行摘要与宿主机源库核对，正文、元数据、向量均一致。摘要只记录 count/hash，不输出数据正文。
4. 用原后端镜像执行 `init_db → alembic upgrade head`，实际 0011 → 0012 → 0013；节点/指纹检查通过。不 stamp、不 ingest、不调用 embedding API。
5. 原外接副本中的 43 节点演练库改名为 `assistant_s4_archive_b20261010`，数据继续保留；已验证开发库副本改名为 `assistant`。内置 VM 中的原库、宿主机 `assistant_dev` 和宿主机旧 `assistant` 没有被覆盖。
6. 应用测试后清理仅本批创建的临时账号、合成会话/任务、测试令牌及隔离恢复库；再次备份净化后的运行库，实际在新的空库还原，27 个源表行数全部匹配，测试数据为 0。隔离恢复库已删除。

最终保留 27 条原成绩、74 个原会话、148 条原消息、26 条原 RAG 请求、395 条引用及 3737 个物理节点。账号和作业仍为原开发库的 0 行；没有把旧演练库的 62 个账号/2 作业/18 成绩合并进来。匿名历史归属未重新分配。

## 本轮验证

| 验证 | 本轮结果 | 边界 |
| --- | --- | --- |
| 原镜像代码默认回归 | 799 passed / 93 deselected，cov 87.79% | 从原镜像导出代码，在本机测试环境运行，不冒充容器内 pytest |
| 当前工作区/新入口门禁 | 835 passed / 93 deselected，cov 87.68%；ruff 通过 | 新入口专项 22 个测试；当前工作区业务代码没有部署到容器 |
| 源库备份恢复 | 真空库事务还原；27 表计数/26 业务表逐行摘要一致 | 不是只检查归档目录 |
| HTTPS 与 account | health/ready=200；真实临时账号登录；无 token=401；匿名旧会话=404；退出后 token=401 | 临时账号随机改密，现已清理；没有建立正式账号 |
| SSE 终态续传 | 经实际 nginx/HTTPS/backend/PG 两次回放 `start→sources→delta→done`；terminal 状态与 ID 一致；终态取消不改结果 | 使用合成持久化记录，无模型生成；不宣称在跑真实模型断线续跑已验收 |
| harness | backend→MCP 列协议、列用例；DNS 参考实现真实编译评测 9/9，100 分 | 源码只在调用内存和 harness 临时沙箱中，未写成绩/长期会话；不是学生作业完整业务闭环 |
| 离线检索/引用 | FTS/路径/已有向量语义召回各有 15 候选；395 条历史引用的 131 个唯一片段全部回读成功 | 使用已存向量，不调用 embedding；不宣称真实问答质量提升 |
| VM 重启 | 四服务/VM 正常停启；就绪、临时账号、同一合成任务/会话和 release 持久保持 | 测试记录之后已清理 |
| 最终备份 | 外接和内置应急副本 SHA256 一致；净化后的最终归档实际还原成功，27 表计数匹配 | 排除 rag_task_inputs 数据；最终账号 0、临时输入 0 |
| 隔离与镜像 | 三应用镜像 ID、PG 容器镜像标记与原运行一致；PG/backend/harness 无宿主机发布端口；harness 非 root/read_only/internal/限额；知识源只读 | 网页仅回环；没有改变学生代码执行边界 |
| 模型/费用 | 当日新增 upstream_usage_records=0 | 没有真实 LLM/embedding、问答质量、负载容量或浏览器交互验收 |

两处验证脚本初始假设与原代码契约不符：SSE 忘记 start 帧；li_ 引用误用了仅服务 legacy 的回读函数。根据原镜像源码改用完整四帧和 li_/legacy 分支后重测通过；没有修改产品代码或放宽产品断言。

## 操作入口

从外接副本执行：

```bash
cd /Volumes/study/dpdk-runtime/workspace/dpdk/ai_chat_assistant
python3 deploy/external_disk.py check
python3 deploy/external_disk.py status
python3 deploy/external_disk.py backup
```

停止前停止新提交并确认后台任务终态；`stop` 会拒绝仍有 pending 的停机。正常停启：

```bash
python3 deploy/external_disk.py stop
# stop 完成后才能在系统中正常弹出磁盘。
# 重新接入正确卷后：
python3 deploy/external_disk.py vm-start
python3 deploy/external_disk.py up
```

复用模式 `build` 被明确禁用；`up`/`postgres-up` 不 pull、不 build，PG 只 start。不要用 `local_harness.sh`、`start_dev.sh` 或手工 Compose 重建来混用原路径、原库及缺失的 PG 镜像 ID。

最新备份在 `/Volumes/study/dpdk-runtime/backups/`，内置应急副本在 `/Users/fsy/Documents/DPDK-Deployment-Backups/current/`；同名 JSON 带 SHA256 和 `restore_verified=true`。本轮证据：原仓库 `.logs/external_disk_B_20261010/evidence/` 与外接 `migration/20261010-B/evidence/`，受限权限且 Git 忽略。

净化后的最终归档为 `assistant-20261010T133332626866Z.dump`，SHA256 `24be631b963971365a54a25888cca24a6406e986142c6b80586dcd195bf9e1e4`。已实际还原验证，包含原数据及必要的 0013 schema，不含本批临时账号/合成会话/任务。

## 验收、后续与回退

- B 的存储复制、既有镜像运行、源库恢复、必要接口、重启和备份恢复验证已完成；用户验收待定。四服务保持运行供核查。
- C 尚未授权：没有把原开发入口切到新环境、没有停用 Homebrew PG、自启动未配置、没有建立首个正式管理账号或决定匿名历史归属。不能把本报告当正式生产切换或当前源码发布。
- D 尚未授权：内置原 `.colima`、项目、数据库均保留，尚未释放它们占用的内置盘空间。
- 如需回退，先停止外接副本，确认不会同时运行同一 VM 的两份副本；再明确使用内置原 Colima home 和原 Compose/本地入口。Docker 默认 context 没有被切换；旧客户端 `colima-dpdk` 名称目前也指外接 socket，回退时须由内置实例启动重新核对生成，不能依赖名字猜测位置。
- 用户将来的真实写入可能令两处数据库分叉，C 必须停写后重做最终备份/对照，不能直接用 A/B 的旧快照覆盖新数据。保留原 VM 也保留了原 PG 容器 rootfs，可作整 VM 回退；数据库逻辑备份不包含容器镜像。
