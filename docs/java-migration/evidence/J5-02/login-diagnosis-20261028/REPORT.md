# J5 登录延迟诊断（2026-10-28）

保留真实性能红：120秒充分预热后，原混合负载测量30秒的login60样本p50 767.325ms、p95 900.224ms，仍超过P0冻结有效上限312.443ms（绝对500ms）。旧2秒预热资格p95为1000.737ms，只有约10%改善；不能用预热不足解释全部差距。资格退出0只代表零错误/正确性/时钟检查，容量信号仍红。

## 已验证的区分探针

新增独立`acceptance/load/diagnose_login.py`只启动同真实应用/独立owned库，真实身份/密码校验/token事务；不改冻结run.py、CapacityServer、统计协议、生产或阈值。每段60实际登录；错峰仍原25ms；先串行再错峰无采样再错峰原同步采样，全部响应/延迟保留。CPU为app+driver的端点累计CPU差/实际段长之均值，**不是**正式持续p95；不采样段仅有RSS端点，不冒充峰值。因隔离登录路径，没有混合问答、正式提交等组，不能冒充容量轮次。

| runtime | probe | 样本 | p50 ms | p95 ms | 组合CPU均值一核% | 错误 |
|---|---|---:|---:|---:|---:|---:|
| Java | serial60 | 60 | 222.214 | 233.091 | 105.514% | 0 |
| Java | stagger60_no_sampler | 60 | 716.903 | 997.596 | 892.121% | 0 |
| Java | stagger60_sampler | 60 | 746.549 | 850.671 | 872.768% | 0 |
| Python | serial60 | 60 | 173.536 | 183.130 | 99.344% | 0 |
| Python | stagger60_no_sampler | 60 | 246.520 | 278.595 | 765.824% | 0 |
| Python | stagger60_sampler | 60 | 220.133 | 235.142 | 743.187% | 0 |

串行Java233.091ms低于312.443ms；但保持原错峰并发时，去掉采样依然997.596ms，启用采样850.671ms。Python同driver/CPU环境则183.130/278.595/235.142ms。该结果支持Java bcrypt在当前并发CPU竞争下的成本放大，否定“纯预热不足”与“主要由driver资源采样造成”。它不证明单纯换线程数、换库或降低cost就会符合全部行为/性能，未做任何生产修复。

## 运行对象与方法归因

真实生产`PasswordHasher.check`→`org.springframework.security.crypto.bcrypt.BCrypt.checkpw`，实际classpath唯一crypto jar为`spring-security-crypto-7.0.8.jar`，SHA256 `346e084a5c4df9f5d6837c7579307b7de10ae7a19d661806b1818f0fcb0353b1`。Python冻结源UserService使用bcrypt.checkpw，安装版本4.3.0。两端真实users hash前缀均cost12，prefix汇总见summary；未输出完整密码hash。

独立JFR-only段记录307个jdk.ExecutionSample，305个栈包含BCrypt.crypt_raw/hashpw（99.35%），leaf299是BCrypt.key、6是encipher，支持CPU热点在bcrypt核心。306栈有截断，故不据此反推完整调用链；样本占比不是精确CPU时间百分比。更早Mac sample无Java JIT符号，保留原资产但不拿它宣称方法占比；其带采样额外profile段p95 2802ms，完全单列不混前三段。

JDK21.0.12.1、原参数`-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k`不变；JFR目标CPUInformation hwThreads10/cores10。同JDK/参数独立Runtime API探针availableProcessors=10；原服务未导出该API，不能把独立探针冒称直接从目标服务读取。全机物理Apple M4/10 logical，且没设ActiveProcessorCount限制。

## 源、错误与资产

原生产ff59同树，protocol始终`e50af779ff1855c540776ff9d6d9c68bef515b5f19b15f41ae51f4ade481ea0d`。Python窄探针01被既有namespace guard拒绝（0测量样本），因新诊断只列单prefix而server要求two-prefix精确一致；只修新diagnostic列表后fresh02完成，失败库/日志也保留。源backend和共享isolation均未改。

五轮新owned库均精确身份/comment/角色/数据目录核验，连接0且全保留；所有driver/app/caffeinate结束。源原文、账本、JFR、Mac sample、原始延迟/资源数据只在gitignored目录，安全统计/raw文件hash见summary。原完整Python基线/故障失败与恢复资产不动。72分钟正式尚未启动；当前需要主Agent决定性能优化或保留失败正式采集路径，不放宽阈值、不减bcrypt12、不隐藏错误。

复现须先显式四PG环境：`python -B acceptance/load/diagnose_login.py --runtime java|python --classpath <既有fresh classpath> --output <新.migration-artifacts目录>`；Java可另`--profile-only`执行独立JFR。该诊断对前三段login冻结p95直接给红绿，Java实测退出1、Python退出0；不作为正式容量pass。
