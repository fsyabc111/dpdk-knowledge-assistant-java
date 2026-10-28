# J5-02 同协议双端短资格（2026-10-28）

source897813c，生产src/main与pom同ff59；`--runtime both --qualification --rounds 1 --warmup 2 --measurement 15`，driver46984退出0。Python、Java各六组的预热/测量请求都有真实样本、零意外错误，9项业务一致性检查均真，clock validity均通过；两端qualification_pass=true。正式容量pass/evaluation均null，layout_gate=false（资格没有正式pair），不视为容量达标。

## 短测观察（六组顺序：login/read/chat/submit/explain_admit/terminal）

| runtime | phase | 六组p95 ms | 组合CPU p95 / RSS峰 |
|---|---|---|---|
| python | warmup | 284.383 / 33.266 / 2261.400 / 219.467 / 21.741 / 2035.159 | 789.542% / 334.859MiB |
| python | measurement | 291.701 / 13.092 / 2165.774 / 216.782 / 24.321 / 2055.192 | 782.063% / 341.344MiB |
| java | warmup | 1115.648 / 37.527 / 2164.780 / 257.274 / 11.128 / 2062.566 | 928.770% / 385.469MiB |
| java | measurement | 1000.737 / 11.963 / 2057.623 / 221.347 / 18.389 / 2049.778 | 901.240% / 395.125MiB |

Java login短测p95约1001ms，高于冻结login有效上限312.443ms/绝对500ms，CPU短窗也超过100%一核。此资格只验证错误/正确性/时钟，并未用短窗替代正式资源门槛；这些超限观察保留并明确报告主Agent。不能调低bcrypt12/负载/功能或抬高阈值。Python、Java真实model每call1s、外部向量50ms（仅实际调用）、MCP200ms、pool32；短窗口资源受60登录burst占比影响，正式仍必须三轮逐轮600s完整判断。当前无正式Java结果。

## 资产和冻结

两端same source/protocol hash；真实后验owned两库均身份/comment一致、连接0，driver及应用PID退出，全机精确Java/Maven/caffeinate进程0。全部输入/日志/资源曲线/manifest保留在`.migration-artifacts/j5-02-both-qualification-20261028-01`，summary只提交去敏统计与原始hash。

原2026-10-26 Python完整三轮基线仍保留。80c9 helper修复改变整协议hash；主Agent决定不加等价豁免、不改旧report/hash，后续须以当前冻结协议重新采Python+Java各3×(120+600)s，共72分钟，再严格组合。P0冻结阈值不重定。正式需主Agent再次确认资源与scope clean后另放窗口；本轮没有启动正式测量。
