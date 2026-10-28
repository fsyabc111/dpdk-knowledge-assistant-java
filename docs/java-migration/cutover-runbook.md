# Java 隔离部署、恢复与应用回退手册

当前版本用于 **J5-03 隔离工程交付**。实时门禁以STATUS.md为准：P4及J5-01核心已过（原metrics raw rc1与唯一政策后的相等结论分列），J5-02故障已过，双端正式容量与J5-03实际运行窗口待完成。本手册的冷启、0013升级、恢复、回退均为pending，不能把镜像build或配置展开写成演练通过。真实上游、生产入口、合并main、Python退役及恢复资产删除不在本轮范围。

## 版本与所有权

| 项目 | 冻结值或恢复入口 |
|---|---|
| P4源码 | f020efa951b816cc3315f169c483af0998875800 |
| P4后端真实image | dpdk-java-isolated-backend@sha256:c6f4799e40d5e5d201de66a04bf2ba62a7a4a9e1fd65ba57af92926fb8d28f11 |
| P4 jar SHA256 | 54b125854754577f861d018bd68d3b745455d046d09a162c0d554bc78eda39db |
| P4前端真实image | dpdk-java-isolated-frontend@sha256:a862bcc150314cc85655e4675035cecef12cb464d56122912cb2800df56cfe52 |
| P5候选 | pending；必须从之后冻结源码真实构建并登记image ID、源码label和归档jar摘要；禁止仅改tag |
| schema | 冷启受控B0015；合成0013_task_recovery→Java唯一迁移入口V0014/V0015；恢复等价0015；不做数据库downgrade |
| PostgreSQL | 0.8.4-pg17锁定digest，实际binary17.10；不能以宿主不同major工具恢复 |
| 知识源 | 原manifest完整10 source/27 include；27个全合成文件，内容与摘要见evidence/J5-03/source-snapshot.json |
| 模型/MCP | Docker内部固定替身；不是live质量，真实harness profile关闭；固定脚本版本由主Agent绑定最终候选 |
| 迁移/执行owner | 每次只有一个Java backend；主Agent控制运行窗口、候选冻结与门禁；deployment worker操作本轮owned资源 |

P4镜像归档保存在本卡worktree `.migration-artifacts/j5-03/build/p4-{backend,frontend}.image.tar`；非秘密摘要在 `evidence/J5-03/p4-images.json`。保留这些归档、专属卷、备份和manifest，不删除恢复资产。源码版本、构建recipe/base digest、配置、知识版本分别记录，不能只写“latest”。

## 环境准备与只读检查

下面所有命令在本卡工作树根目录执行。首次准备命令拒绝已存在目录，不覆盖失败或旧资产。

```bash
python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v
python3 acceptance/deployment/prepare.py --repo "$PWD"
export DPDK_JAVA_ENV_FILE="$PWD/.migration-artifacts/j5-03/runtime.env"
export DPDK_JAVA_DOCKER_CONTEXT=colima-java-u12
export DPDK_JAVA_DOCKER_ENDPOINT="unix://$HOME/.colima/java-u12/docker.sock"
# 新profile已由owner建立/恢复后，只读取得现场身份；不得使用旧profile的值。
export DPDK_JAVA_DOCKER_DAEMON_ID="$(docker --context colima-java-u12 info --format '{{.ID}}')"
docker --context colima-java-u12 context inspect colima-java-u12 --format '{{json .Endpoints.docker}}'
docker --context colima-java-u12 info --format 'ID={{.ID}} Name={{.Name}} OSType={{.OSType}}'
scripts-java/compose.sh
python3 acceptance/deployment/rehearse.py --repo "$PWD" plan
```

首次接手Mac需先由owner核对`colima list`及仓库实际路径；只在授权的新环境准备窗口创建或恢复java-u12，不能在容量窗口启动VM。当前工具的首次命令为`colima start --profile java-u12 --cpus 4 --memory 4 --disk 20 --mount "$PWD:w"`；已有profile先核对其配置/持有资产，不覆盖或重建。禁止启动旧dpdk或切换全局context。上面的现场endpoint必须等于当前HOME下的新socket，Name必须colima-java-u12、OSType必须linux；daemon ID和该round资产由owner核对后固定。此项目守卫专门针对本批次Mac Colima，不宣称任意Docker环境通用。

本轮历史daemon ID为8f66e5ea-9c07-4379-b27d-7a5759cc2cd3、endpoint位于/Users/fsy/.colima/java-u12/docker.sock；它们是当前机器资产记录，不是新维护人复制的默认值。旧DOCKER_HOST/TLS/DOCKER_CONTEXT环境必须在本终端显式排除；不修改用户全局配置。

本卡当前已经准备过，接续时使用现有runtime.env与manifest，**不要重跑prepare**。口令随机生成，env与私钥均0600且Git忽略；禁止打印、提交或复用旧.env。生成器只读取知识源清单，绝不复制原项目源内容。TLS证书SAN包含localhost/127.0.0.1，HTTPS使用证书校验，不使用curl -k绕过校验。

默认Compose配置展开和plan不连接daemon/PG；仍要求显式新context配置。实际动作额外核对context inspect endpoint、docker info daemon ID/Name/OSType；拒绝DOCKER_HOST/TLS或旧DOCKER_CONTEXT环境。全局Docker context仍可保持旧值，因为每一次Docker命令显式 `--context colima-java-u12`。profile若尚未恢复由主Agent按其已授权窗口处理，本脚本不启动/切换Colima。

每一round使用manifest中的 `dpdk-java-isolated-<12hex>` project及新卷，避免冷启复用有状态卷。PG没有宿主端口，HTTPS只绑定127.0.0.1:18443。脚本拒绝外部DB URL/旧库、非owned overlay、非记录image、改变role/data_dir/网络/权限的overlay。JSON overlay仅允许两应用image/build=null与backend三个DB目标字段，文件必须本轮owned目录且0600。

## 构建与登记候选

P4镜像已经真实构建，source label、image ID、归档摘要和jar摘要均已记录。只有P5候选冻结后才补候选构建；在冻结checkout核对应用路径无未提交源码变更，再从该checkout构建两个镜像。两个Dockerfile的base均已按主Agent已拉取digest锁定，不擅自升级。

新round的manifest.images初始为空。先从owner取得P4两份归档（可从已有资产复制到新round，原资产保留）；逐项核对evidence/J5-03/p4-images.json的archive_sha256后才能load。归档目录必须是当前round的owned目录，文件不符则停止，不能从相同tag推断P4版本：

```bash
mkdir -p "$PWD/.migration-artifacts/j5-03/build"
# p4_asset_dir为owner提供的既有归档绝对目录；先核对两份SHA，再load，不覆盖原归档。
python3 - "$p4_asset_dir" <<'CHECK_P4'
from pathlib import Path
import hashlib,json,sys
records=json.loads(Path('docs/java-migration/evidence/J5-03/p4-images.json').read_text())['images']
for row in records.values():
    p=Path(sys.argv[1])/row['archive_name']
    assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==row['archive_sha256'], 'P4 archive differs'
CHECK_P4
docker --context colima-java-u12 image load --input "$p4_asset_dir/p4-backend.image.tar"
docker --context colima-java-u12 image load --input "$p4_asset_dir/p4-frontend.image.tar"
python3 acceptance/deployment/images.py --repo "$PWD" --role p4-backend --image dpdk-java-isolated-backend:p4-f020efa --source f020efa
python3 acceptance/deployment/images.py --repo "$PWD" --role p4-frontend --image dpdk-java-isolated-frontend:p4-f020efa --source f020efa
```

若新round与资产所在round相同，核对原manifest/image/save摘要即可，不重复登记或覆盖归档。P4源label必须f020efa完整SHA，新旧不能只改tag。新机器拿不到已记录P4归档时，先取得该源码与精确recipe/base重建并单独记录新的实物hash，不能宣称等于历史归档。

```bash
# candidate_checkout由主Agent给出，必须是冻结的本仓库checkout绝对路径。
candidate_revision="$(git -C "$candidate_checkout" rev-parse HEAD)"
git -C "$candidate_checkout" diff --quiet "$candidate_revision" -- backend-java frontend
docker --context colima-java-u12 build --pull=false -f "$PWD/deploy-java/Dockerfile.backend" \
  --label "org.opencontainers.image.revision=$candidate_revision" --label io.dpdk.validation-domain=isolated-synthetic \
  -t "dpdk-java-isolated-backend:candidate-$candidate_revision" "$candidate_checkout"
docker --context colima-java-u12 build --pull=false -f "$PWD/deploy-java/Dockerfile.frontend" \
  --label "org.opencontainers.image.revision=$candidate_revision" --label io.dpdk.validation-domain=isolated-synthetic \
  -t "dpdk-java-isolated-frontend:candidate-$candidate_revision" "$candidate_checkout"
python3 acceptance/deployment/images.py --repo "$PWD" --role candidate-backend \
  --image "dpdk-java-isolated-backend:candidate-$candidate_revision" --source "$candidate_revision"
python3 acceptance/deployment/images.py --repo "$PWD" --role candidate-frontend \
  --image "dpdk-java-isolated-frontend:candidate-$candidate_revision" --source "$candidate_revision"
```

本地构建后的镜像引用必须来自实际`docker image inspect`，不能手写一个假registry digest。images.py只做image inspect/save和离线归档摘要，不创建运行容器；真实source label不符时拒绝。归档保留实际jar/静态资源hash。回退执行器要求新旧image ID、源码revision与jar摘要全部不同，不能只给同一镜像换tag冒充回退。若候选尚未提供，plan明确pending，不运行后续场景。

## 实际窗口内的顺序

执行条件是J5-01核心及J5-02故障/容量门禁通过、候选冻结、主Agent给出共享资源窗口。它们是本轮已有的工程调度条件，不是额外用户审批流程。当前条件未满足，以下命令尚未执行。

```bash
python3 acceptance/deployment/rehearse.py --repo "$PWD" cold --execute
python3 acceptance/deployment/rehearse.py --repo "$PWD" upgrade --execute
python3 acceptance/deployment/rehearse.py --repo "$PWD" backup_restore --execute
python3 acceptance/deployment/rehearse.py --repo "$PWD" rollback --execute
```

- cold：先拒绝既有本round数据卷→固定image启动全新栈→TLS/API存活→空索引ready503→完整全源CLI预览/ingest→ready通过→账号创建/改密→真实HTTP聊天持久化、引用回读、SSE增量与done→外部metrics/actuator404。
- upgrade：先停止唯一backend→核对新PG容器project/service/卷及维护库role/data_dir、base库comment→创建manifest-owned `p5_deployment_<round>_upgrade`→在该空库构造合成0013及历史成绩→切候选到此库，由Java唯一入口升级→索引与业务读写→历史成绩保留。该合成构造沿用MigrationThreePathsIntegrationTest，不对原库执行downgrade。
- backup_restore：停止backend冻结合成写入→仅临时输入表放置源码哨兵→长期表哨兵为零→custom pg_dump仅排 `rag_task_inputs` **数据**→pg_restore --list确认表结构存在且无TABLE DATA→创建另一个owned空库restore→实际pg_restore（单事务、exit-on-error、无clean/create覆盖）→动态枚举全部public表，完整行canonical JSON→SHA256，按行摘要C序聚合为每表多重集摘要（保留重复行），对比行数/完整行摘要/列结构摘要；临时输入表保留列结构但恢复数据为空，长期哨兵为零→候选启动并回读原消息/旧引用，再写入新业务。
- rollback：停止新backend→切回已归档P4旧应用image，数据库维持恢复后的新数据和schema→旧应用读取升级前后消息、引用和新写入业务，再实际新写入→不还原旧备份、不降低schema、不损失增量。PG始终只有一处单写/迁移owner。

每个CREATE/DDL/restore前都重新核对新daemon和PG数据库/role/comment/data_dir；unknown或已有库不会自动收养，非空恢复目标拒绝。没有DROP/down -v/清卷入口。阶段失败会记录failed与异常类型，原始命令失败输出仅留ignore本地日志；保存现场后定位，不能盲目重跑或覆盖manifest/备份。需要新round时先完整保留失败round目录，再由主Agent建立新的专属准备目录和project；不删除旧恢复资产。

## 证据与RTO/RPO口径

`scenario-state.json`含合成账号密码等恢复状态，0600且不得提交；`scenario-results.json`只记录断言、计数、hash、固定身份与时长，提交前再做白名单审查。最终报告必须绑定源码、image ID、jar、source manifest、配置摘要、schema路径、实际PG major及fake脚本版本。build、自动验证、场景验收和用户验收分别写。

- 本卡恢复时长包含停止backend、pg_dump/创建新库/恢复、应用启动、健康/ready和业务读写检查；各阶段另有execution_seconds。冷启包含镜像已存在时的容器/数据库初始化与ingest，不含拉镜像/build。报告注明测量边界，不能套用thresholds.json的1秒局部ensure_managed恢复阈值。
- 备份恢复RPO只验证受控停止写入后的备份快照：应保留业务表、消息、身份、成绩、用量、release及旧引用；临时执行输入按既定规则有意排除。行摘要覆盖所有public普通/分区表的完整行，含新增业务表；不把11表count相同当数据相等。只输出表名、count与SHA256，不导出密码哈希/token/业务正文/源码。rag_task_inputs仅列结构摘要，数据有意排除。该行摘要不证明sequence值、约束或索引定义等完整catalog等价；这些仍依赖迁移/恢复及业务验收。该结果不能证明主机/PG故障、备份间隔增量或真实MCP在途作业RPO=0。
- 生产停机窗口、真实RPO、真实模型/harness质量和不可逆数据处理均未在本批次批准或测量。固定替身内存丢失不等于真实harness恢复，需独立场景说明。

## 回退触发与生产边界

权限/身份/数据错误、ready失败、恢复不完整、源码哨兵泄漏、冻结容量门禁失败或超时未终态均阻止放行。先停止新写入并登记/排空活动任务，保存新数据与作业账本，确保旧/新后端不同时恢复同一任务。优先已实测的应用镜像回退；新schema或新增业务若旧应用不能读，保持写冻结并走已验证的前向修复或独立恢复路径。不得拿旧备份覆盖切换后的增量。

本手册不授权改生产入口、停旧服务、删除原仓库/镜像/备份或退役Python。若未来另行授权真实切换，仍需当时精确目标、账户/预算/数据、单写owner、已实测备份恢复、观察窗口及用户确认的数据处理策略；本卡的隔离工程证据不能替代这些事项。

## 手动维护的当前目标与内部观测

执行器把当前应用overlay保存于本round600权限scenario-state.json，升级/恢复后shell不会自动继承它。手动备份必须选择并核验当前overlay，dry-run输出database应等于实际backend DB_NAME；不一致停止，不能因为BASE库身份也合法就备份旧库。包装器从可信600/currentUID state选择overlay，拒绝显式过期overlay，apply再核对实际backend image及DB URL/name/comment；尚无state只允许显式`backup.sh --base <新绝对路径>`且无override；实际备份追加`--apply`（`backup.sh --base --apply <新绝对路径>`），两者均不得用于已有upgrade/restore state。apply仍核对实际backend。dry-run输出actual_backend_verified=false，不可当作已连接验证。实际base/upgrade/restore验证见J5-03最终报告；使用全新目标文件，不覆盖business-backup.dump。

内部指标仅在新project内获取，不向宿主开放后端metrics；日志只保留本地ignore，摘录前去敏：

```bash
scripts-java/compose.sh --apply exec -T fake-upstream node -e "fetch('http://backend-java:18090/metrics').then(async r=>{if(!r.ok)process.exit(1);process.stdout.write(await r.text())})"
scripts-java/compose.sh --apply logs --tail 100 backend-java
```

`retrieve`当前CLI只接受显式fixedQueries固定查询向量，专用reader不接embedding端口，单配真实embedding也不会成为CLI正例；`judge --cases`要求已存在且挂载的合成case文件，prepare默认只生成知识源，不生成cases.json。这两个OPERATIONS示例是配置缺失时的预期拒绝/skip检查，不是默认正例；同jar的固定fixture正例由evidence/P5/validation-code.json所绑定CLI验收覆盖。不得为让示例返回成功而默认外发。
