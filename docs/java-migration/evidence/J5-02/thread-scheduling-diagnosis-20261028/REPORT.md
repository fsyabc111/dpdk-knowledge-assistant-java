# J5 登录线程调度变量实测（2026-10-28）

两个独立owned轮次使用同真实HTTP登录诊断，不改生产/算法/pom/编译参数/原run.py/diagnose_login.py/冻结协议/阈值。仅gitignored wrapper在launch时显式传JAVA_TOOL_OPTIONS；每轮只改一个部署变量：①`-Dspring.threads.virtual.enabled=true`；②仅`-Dserver.tomcat.threads.max=10`，非virtual，绝不组合。原5个JVM内存参数、Spring BCrypt7.0.8、cost12、60账号和25ms错峰都保持。两轮诊断均退出1，表示冻结login p95信号仍红，不能把零HTTP错误说成容量过。

## 三段性能（每段60次真实成功登录）

| 配置 | 探针 | 样本 | p50 ms | p95 ms | 错误 |
|---|---|---:|---:|---:|---:|
| virtual | serial60 | 60 | 233.512 | 268.566 | 0 |
| virtual | stagger60_no_sampler | 60 | 781.619 | 1025.301 | 0 |
| virtual | stagger60_sampler | 60 | 759.372 | 1073.205 | 0 |
| max10 | serial60 | 60 | 233.008 | 241.447 | 0 |
| max10 | stagger60_no_sampler | 60 | 553.153 | 749.222 | 0 |
| max10 | stagger60_sampler | 60 | 560.761 | 765.684 | 0 |

virtual未降低延迟；max10较原平台默认错峰997.596/850.671ms降到749.222/765.684ms，但仍大幅高于冻结有效上限312.443ms（绝对500ms）。三个纯Javaprovider和JIT微基准属于root另片，本片不借用其结论，也不依据这些结果擅自选择部署配置。

## 配置确实作用于实际executor

前三段没有额外profile或业务流量；VM.info/初始线程快照在所有计时之前，第四个独立60登录段才使用JFR（其额外开销完全单列）。不把profile段混进三段延迟。

- virtual：JFR631个执行样本，630个sampledThread.virtual=true，来自60个不同tomcat-handler线程；1个是platform Poller。BCrypt核心leaf key623+encipher6；启用项实际生效，不是只设置了字符串。
- max10：658个样本全部virtual=false，采样恰为exec-1至exec-10共10个实际Tomcat执行线程；线程dump亦恰10个executor平台线程。BCrypt核心leaf key651+encipher5。该证据支持实际执行器的10线程布局，未通过减请求数偷换负载。
- 目标运行中VM.info两轮均明确`CPUs: 10 total, 10 available`，补齐前轮只能用同参数独立API探针的限制。实际目标JDK21.0.12.1、原5项内存参数+唯一对应-D；未设置ActiveProcessorCount或更换线程编译策略。

合计480次登录（前三段两轮各180，独立profile两轮各60）全部零错误。仅该诊断调用身份/密码/token真实业务，不包含混合问答及所有正式容量端点，不能代替完整72分钟。CPU为各段app+driver累计差/耗时均值，不冒充正式600秒持续p95。

## 后验与下一步

源96efb8a业务仍同ff59；两轮protocol保持`e50af779ff1855c540776ff9d6d9c68bef515b5f19b15f41ae51f4ade481ea0d`。wrapper只在内存替换launch/probe，磁盘load与生产文件逐路径diff为空。全部PID已退出，精确Java/Maven/caffeinate进程0；两owned库精确comment/身份核验，连接均0，全部资产保留。原始VM.info可能含环境，故只在gitignored保留；本summary仅白名单统计/线程名/源码与文件SHA，不提交原始环境/日志/JFR。

当前无得到解决的安全调度收益，不改变正式JVM参数、算法、cost、负载和阈值。72分钟尚未启动，按主Agent后续确认再用原flags完整双端3×(120+600)s严格采集判定；不能预先宣称容量通过，也不清掉已有失败。
