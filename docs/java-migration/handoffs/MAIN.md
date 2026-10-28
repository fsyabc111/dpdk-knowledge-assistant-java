# U12主交接：2026-10-28收束，批次未完成

**本轮已按用户新收束指令停止推进；P5未放行。** 先读../STATUS.md，再按任务ID读证据/交接。此前长恢复点保留于archive/MAIN-U12-pre-closure-20261028.md，本文件优先。

## 1. 用户指令与停止线

原U12允许P3→P4→P5隔离工程交付和J5-04准备，最多3 worker、小步提交及时推送。17:38左右用户追加：收到后30–60分钟收束，不再强行完成全部P5；停止JNI/Rust等新技术支线，保留所有原型/partial/失败/恢复资产；不新起72分钟正式容量或长验证/排障/架构改造。当前只做现有修复必要验证、证据、提交推送与资源核验。

后续需用户新指令，不默认重启Rust/JNI、工具安装或长容量。真实模型/embedding/harness验收、main合并、生产切换、Python退役、恢复资产删除、旧项目/部署/业务库写入仍禁止；工程/场景/用户验收分开。

## 2. Git、源码、工件

- 仓库 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java；分支codex/p3-p5-longrun。origin用户仓库https://github.com/fsyabc111/dpdk-knowledge-assistant-java.git；push经SSH443，同名开发分支。无PR合并/发布。
- 当前stock Java业务源码固定2a950d7f660dba4b4ac0d70cf6e3485a0cde668b；其后仅证据/交接。src/main tree6e1ec8a514e9ea0151fbc9848b8e4c6f1e6be3e3，Java subtreee77ed11ba386eb047dbff10eed0e1a3f3f78ec43，SingleFlight blob06a302be5495563e6bd96e0fbc72e28239023050。
- 新jar SHA9393c8dd25101e0f83d814061d2cc38d4a8ea61efed20a7f198df382d7e6f5f3；root target与独立README重跑得到同SHA。保留副本 .migration-artifacts/recovery/jars/<SHA>.jar。旧P4 native jar ef1b4f8e...、ff59 jar14caee...均在同目录，不能删除。
- 原Python行为基线80b29135faddd16d5e39fdbf0633291ea6a20489，backend tree38efd2a4d4ad284726989974bbb521ec4d4801dd。本轮backend/oracle/p0-v2黄金/thresholds未改。pom SHAa6a04b7167194ba9b1df38d04fb62b08c4e232ae0da7c4e109c92d91e4fb55cc；frontend lock SHA8a15f4eff452e1557df0e7fd932f2efa830d784c9c7a9cb016d95bab2a768a19。
- 收束文档提交后的最终HEAD/remote相等、clean回执保存在ignored .migration-artifacts/u12-closure-20261028/final-git-state.json；最终回复另列已推送SHA。本文件不把自身commit SHA写进自身形成循环。远程核验只用精确同分支ls-remote，不改网络配置。

## 3. 本轮已完成证据

- P3 5d0d6a3/591项/两轴4阻断关闭；P4 f020efa/707项/CLI2及16 JVM/CI40198306859四job/双轴通过，历史阶段报告不重做。
- 源过程指标补齐：用户拒绝12 unavailable/gauge豁免，已真实counter/histogram+业务hooks；当前2a950源root重新运行正式Application/真实PG/loopback外部替身，16领域实际观测、25固定断言、46family236series。evidence/J5-01/metrics-mapping-causal-2a950d7.{json,md,prom}。fixture known_event_domain中的ff59只是原标识，不冒称当前源；本次actual source/哈希独立绑定。
- J5-01当前fresh full08（workerd40189b→main5039e3a）：186观察、198步、25表，原CLI rc1仅4个runtime TYPE descriptor；唯一metrics单case policy后完整trace相等，30共同descriptor精确、4实际JVM能力单列，不伪称Python对象量等价。新225扩展/原17(1正16负)/policy28(2正26负)/纯30全部符合预期，false-pass0。两库dropped、进程全退。原oracle/golden/corecompare未改，业务数组不归一；政策双轴已过，见P5/review-*-metrics-policy.md。
- 缓存CI修复：CI40656367531/b55fa80的postgres曾780发现/1error/3skip，失败立即retry仍拿旧failed future。code4a6/8d9/36c4→main70f1fbb/7e67098/2c900d4：managed future先收口cache/inflight再发布；clear按冻结Python identity栅栏，旧waiter仍完成、旧loader不能回填/覆盖/删新flight；sync throw/null无dangling、shield不伤共享raw。两组确定性红保留，相关109/109；独立规范/规格窄复审无阻断。evidence/P5/ci-singleflight-publication.md、review-*-singleflight-final.md；interface-changes/J5-CACHE。
- root当前完整verify本次789发现/786执行全过/3 DefaultBoundary skip（不是791；旧独立BootJar2 XML不能加进本次）。同9393 jar额外CLI2/2含16 JVM、0skip。最新CI40662932995固定2a950四job全部success，已查明细。validation-code.json是当前；validation-code-ff59.json为历史。
- 独立README读者5039e3a源码同2a950，另实跑789/786/3skip，前端npm ci+102/102+build、部署36纯测、CLI help全过。实际Node24.18.1，不冒充Node22；主checkout缺j5-03/env/manifest所以没跑compose/plan，更没跑四实际部署场景。报告evidence/J5-04/readme-independent-build-20261028.md。
- 浏览器历史22+最终5保存场景（各源在browser-progress和browser-final-20261028.json）：原前端多角色/跨班/作业/成绩/讲解/6引用/旧release/反馈/发布/CSV/Markdown消毒，真实首帧约62ms早于10秒模型，取消刷新持久通过。该轮源在缓存修复前，未在最新2a950再跑，不把历史UI当最新源全部通过。冻结源leader直属学生树缺陷另列known-source-ui-gap.md，未扩范围改前端。
- J5-03准备：备份不再误选合法但非活动base库；可信600 state/overlay及apply真实backend image+DB目标核验，36工具纯测试通过。OCI index/manifest/config及legacy ID严格链，P4两真实归档全部blob/diffID/jar/assets/原SHA重验通过，旧ref/tag与manifest字节不变，报告p4-full-archive-revalidation.json；独立OCI复审已关P2。候选image与四业务场景仍没有执行。
- J5-04依赖：Maven100/npm213声明、JAR/npm原文去重归档；7锁定image728 installed包/1390 license路径库存，实际1390原字节全SHA吻合，container域307去重对象约2.54MB。Node22.23.4与pgvector0.8.4官方对应原LICENSE也已归档并核Git blob/sourcecommit。缺全文/声明局限逐项在索引，不签完整法律NOTICE。容器只禁网readonly/nobody/tar query，无应用或PG启动。

## 4. 硬阻塞与未完成

冻结login有效p95门槛312.443ms（还须≤绝对500ms），bcrypt12不降。原Java120秒预热+30秒测量仍p95900.224ms；窄真实HTTP串行233.091、60×25ms错峰无采样997.596/有采样850.671ms。Python同driver串行183.130、错峰278.595/235.142ms。JFR307样本305包含BCrypt核心，支持并发CPU成本放大，不把样本率称精确CPU时间比例。其余组/正确性/clock通过不抵扣login；短资格true不等正式容量pass，短窗CPU860–900%不当正式600秒CPU判定。

Spring/Password4j/Favre/Bouncy Castle微基准、定点inline、VT和Tomcatmax10诊断均未取得足够收益，未改生产密码库/参数。source/随机盐/排队/首次下载干扰局限明列于bcrypt-exploration报告；不取较绿值冒充HTTP门禁。当前仍stock Spring BCrypt7.0.8。

- 原Python完整3×120+600（source0f03c14）已有效，全部资产保留，candidate pass=null；80c9 helper修正新job必须先unknown再真实submit改变protocol_sha256，因此旧报告不能与新Java严格配对。compare_pair不加事后例外、不改旧hash、不抬阈值。
- 历史all02（sourcefa066a1，生产ff59）14/14含11真SIGKILL、clock/41queue/lifecycle及12组长期表/log/真实dump哨兵通过；对应源与RTO域在报告中，不继承为2a950最新全故障。当前3 boundary skip未补跑。1秒只accepted/before_terminal已ready执行段，不含启动/30sgrace/300s扫描。
- 同协议完整双端3轮、最新源默认边界/故障、最新源重点UI未完成。本轮未启动72分钟正式采集，不能宣称正式容量已跑完或最终判pass。
- J5-03 P5候选不可变build和cold/合成0013升级/实际dump另一空库恢复/旧P4应用回读新数据并新写均未执行。容量未过且用户收束，本轮不越门禁做场景。最终manifest、完整独立部署复现、P5总门禁及用户验收均未完成。

## 5. 已停止的JNI/Rust支线（不得默认续跑）

仅候选可行性：官方pyca4.3.0使用Rust bcrypt0.17.0；薄JNI草稿想复用该链，不是成熟可交付实现。root .migration-artifacts/bcrypt-jni-proof-20261028仅7个ignored源码/审计/原pyca锁，proof自己的Cargo.lock尚未生成，未cargo build/javac/互验/bench/HTTP验收，未入pom/生产classpath/jar。详handoffs/J5-BCRYPT-JNI-PROOF.md；编译器/平台/panic/错误语义都未验收，不能据“同源”当兼容已通过。

隔离Rust工具根 .migration-artifacts/rust-isolated-20261028：官方manifest与rustup-init SHA通过，Rust1.99.1 minimal安装未完成；cargo文件有、rustc缺失，不可用。CARGO_HOME=该根/cargo，RUSTUP_HOME=该根/rustup，未来PATH前缀=该根/cargo/bin；仅子进程注入，全局shellRC/.cargo/.rustup存在状态无改。下载协调器70198、8 curl、installer均受控退出，无SIGKILL/安装原子写入被粗暴截断；约273MB partial/失败/安装记录全部保留。工具恢复说明该根RESUME-NOT-AUTHORIZED.md，仅供新指令后使用，当前不得执行。证据J5-04/rust-isolated-preparation-stopped.json。

## 6. 资源终态与恢复资产

- **专属native PG已smart停止**：root先核current_user=p1_java、port55481、data_directory精确本root/.migration-artifacts/p1/pgdata，确认其他client0再停止。已要求保留的恢复/故障/容量/诊断库及data目录/备份保留；普通fresh TestPg/UI/矩阵临时库按原owner生命周期精确清理（各manifest有记录），未触碰旧业务库。
- java-u12与旧dpdk均Stopped；只新profile被本轮启停。所有Docker始终显式--context colima-java-u12；endpoint unix:///Users/fsy/.colima/java-u12/docker.sock、daemonID8f66e5ea-9c07-4379-b27d-7a5759cc2cd3、Name colima-java-u12。全局context仍colima-dpdk-ext。无runtime app/PG业务栈启动，无down -v/prune/镜像或卷删除。
- UI controller42703/Java42715/Chrome42704及本轮crashpad42707已退；matrix/metrics/README/JVM/安装下载本轮全部退出。旧无关应用不操作。worker当前任务交回；旧worktree/untracked资产不清理。
- P4 Docker归档：root/.migration-artifacts/worktrees/j5-03/.migration-artifacts/j5-03/build/p4-{backend,frontend}.image.tar，SHA在evidence/J5-03/p4-images.json；P4 immutable backend c6f4799e...、frontend a862bcc1...仍保留。
- J5-03仅prepared project dpdk-java-isolated-747eddb2a98a及合成27文件/env/TLS/manifest，位于j5-03 worktree的.migration-artifacts/j5-03；600文件含秘密不打印/提交。只有P4两image记录，candidate槽位空，scenario-state/results不存在。
- 旧Python正式基线：j5-02/.migration-artifacts/j5-python-baseline-20261026-01；all01失败、all02/clock/accepted/mcp资格/诊断各目录都保留。原始输入/model输出/log/PG dump不提交；各去敏报告含hash索引。
- 当前code r3 log root/.migration-artifacts/p5-singleflight-full-verify-r3.log；CLI log p5-singleflight-native-cli.log；metrics原件metrics-causal-singleflight-20261028；独立README原件readme-independent-build-20261028-2wwfc03e（准确目录以报告为准）。最终resources.json与Git回执在root/.migration-artifacts/u12-closure-20261028/。

## 7. 以后获得新指令后的准确入口（本轮不执行）

从本repo根恢复，先核Git branch/HEAD、源码tree、当前STATUS和各owned manifest。不要从partial Rust/toolchain启动程序；不要自动续跑JNI或72分钟。

专属PG恢复仅同一既有data目录（不initdb、不降级、不读旧.env）：

```sh
/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D "$PWD/.migration-artifacts/p1/pgdata" -l "$PWD/.migration-artifacts/p1/postgres.log" -o '-p 55481 -h 127.0.0.1 -k /tmp/dpdk-java-p1-80b29135' start
```

明确4项DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR（URL jdbc:postgresql://127.0.0.1:55481/postgres，USER p1_java，DATA_DIR root/.migration-artifacts/p1/pgdata；密码仅从root/.migration-artifacts/p1/local-credentials.txt指定DPDK_TEST_PG_PASSWORD键安全注入子进程，不打印、不source旧.env）。先只读身份核验，再由TestPg/PgIsolation创建唯一新库/精确comment。JDK21.0.12.1路径/opt/homebrew/opt/openjdk@21；Python root/.migration-artifacts/p0/venv/bin/python（3.11.15），Node实际24.18.1。

新窗口可先fresh编译classpath，再按所放行步骤执行；每次全新输出目录，不能覆盖证据：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$PWD/.migration-artifacts/NEW-classpath.txt"
# 以下需四项PG显式环境及主Agent独占窗口；只有另行放行后才执行。
.migration-artifacts/p0/venv/bin/python -B acceptance/resilience/run.py --case all --classpath "$PWD/.migration-artifacts/NEW-classpath.txt" --pg-bin /opt/homebrew/opt/postgresql@17/bin --output "$PWD/.migration-artifacts/NEW-all"
# 正式双端仍须先解决/裁定原门槛下登录问题，并重新明确72分钟窗口；不缩时/改阈值。
caffeinate -i env PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime both --rounds 3 --warmup 120 --measurement 600 --classpath "$PWD/.migration-artifacts/NEW-classpath.txt" --output "$PWD/.migration-artifacts/NEW-paired-capacity"
```

此后只有J5-02门禁真实通过才按唯一cutover-runbook做J5-03候选build/四场景，并补最终manifest与完整独立接手。真实上游/合main/生产切换/Python退役/删除恢复资产仍各有独立授权边界。
