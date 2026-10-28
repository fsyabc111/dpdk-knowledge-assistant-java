# 独立接手资料：静态复核

2026-10-28。固定修复快照 `8ed2189a9e8adea70f35e8baba1dfec729fb0e78`，对照原`437a886`报告。另读取了主工作区backend README的边界skip补充，单独视为工作区材料，不将其计入8ed提交。本次仅轻量读取源码、文档和既有P4归档元数据；未运行测试、构建、PG、HTTP或Docker。没有实际独立复现结论。

## 四组关闭情况

1. **备份选库：代码静态关闭，文案尚有小项。** `acceptance/deployment/backup_cli.py:13-31`从当前用户owned、mode600普通state文件选当前overlay并validate；过期显式override拒绝，无state不再默选base，须`--base`且不能带overlay。`:45-74`在apply前检查owned project/service的运行backend、实际image ID及DB URL/name/comment，再调用PG备份；dry-run明确`actual_backend_verified=false`，不伪称现场已核验。原“合法但备错base”路径已封锁。runbook还应补无state时`backup.sh --base <新路径>`及`--base --apply`的限定用法，不能把该例用于已有upgrade/restore state。

2. **镜像首次归档：仍有P2实物兼容阻断。** build目录创建、无RepoDigests时采用真实ID、P4 SHA核对/load/登记步骤已补；但`images.py:47-48`强制“archive config SHA == inspect Id”，不适用于现有containerd实物。只读本轮保留的两份真实归档：

| 既有归档 | p4-images.json记录的image ID/归档index descriptor | archive manifest所指config的实际SHA |
|---|---|---|
| p4-backend.image.tar | `sha256:c6f4799e40d5…` | `sha256:124ecf351b81c36d735a6ea9025d8278e00d840d10744634140dbf555be5e0e0` |
| p4-frontend.image.tar | `sha256:a862bcc150314…` | `sha256:773ae2d0fb407e38b4860d8f3d3f5d5d45a6a366da9bb4a338e7eda55657de5f` |

归档位于主worktree `.migration-artifacts/worktrees/j5-03/.migration-artifacts/j5-03/build/`。两份`index.json`均为OCI index，其根descriptor digest正好等于已记录image ID；`manifest.json[].Config`则指向上表不同config blob。因此有效P4镜像按新代码会被拒绝。只读取外层metadata及小config blob并计算config摘要，未重扫整档/layer或启动Docker。新纯测`test_backup_images_review.py:37-45,49,87`均把模拟Id设为config摘要，没有覆盖此OCI index身份域，8项/全30项纯绿不能关闭该问题。

应同时支持legacy config-ID与containerd index/manifest-ID，校验实际根对象摘要及其descriptor→目标平台manifest→config/layer链，不能简单删掉身份检查。补现有P4格式的离线正例和根/child/config篡改反例；真实save/load与登记仍待窗口。

3. **首次环境：静态关闭，现场仍待运行。** runbook`:31-42`改为当前HOME、现场ID/endpoint/Name/OSType，补Mac新profile建立/恢复责任及历史值的适用范围；backend README补独立native PG资产前置、不覆盖旧pgdata、不自动找库及先建候选库/comment。实际新profile、全局context保持、PG权限/扩展必须在后续窗口核验，不能以文档存在计通过。

4. **状态/操作说明：大部分关闭，留P3一句。** J5-01政策通过、J5-02故障通过、双端正式容量待完成已与STATUS分开；retrieve/judge缺配置例子明确为预期拒绝/skip，并补内网metrics/日志命令。但runbook`:136`的“retrieve固定查询向量或真实embedding配置”应删去后半：`OperationsBusinessAdapter.java:96-102`只接受fixedQueries，OperationsWiring专用reader的embedding端口为null，单配真实embedding不会成为CLI正例。主工作区backend README关于verify实际3 skip与owned `--case clock`另验3项的补充方向正确；应提交绑定，旧报告“完整PG必须0 skip”不能机械套用为新门禁。

## 实际窗口剩余最小验证

- 关闭OCI身份判断后，用既有P4真实归档核对不变性，再在新owned round登记四image角色；验证本地无RepoDigests、重复登记不改manifest、config/layer/root被篡改拒绝。保留原档，不覆盖。
- base（无state显式--base）、upgrade、restore分别核对dry-run选择和实际backend；过期override/错误DB目标应在pg_dump前拒绝，正确目标才apply。
- 按已修runbook完成新栈cold→全源ingest/ready→0013→备份/另一空库恢复→P4应用回退；同时检查TLS/SSE、旧引用、完整保留行证明、临时输入、内部metrics与日志。
- 记录本次构建/测试实际pass/fail/skip；3项DefaultBoundary须owned clock真实结果另列。容量、部署、用户/live边界不变。

**结论：原第1/3组代码/环境前置静态关闭；第2组因现有containerd实物被误拒仍阻断；第4组留窄文案修正，--base说明亦待补。实际独立复现仍未执行，不支持据此宣布接手验收完成。** 仅新增此报告，不修业务、不提交。
