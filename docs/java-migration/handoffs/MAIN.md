# 主Agent恢复点

## 2026-10-26 U12，P5进行中

- 授权：P3→P4→P5隔离工程交付/J5-04准备，最多3worker，小步提交推送。禁止live/main合并/生产切换/Python退役/删除恢复资产/原环境写入；不代签用户验收。
- P3已过5d0d6a3/591项/双轴4阻断关闭，收口708128c。P4已过f020efa/707项/同jar CLI2项16JVM/CI40198306859四job/双轴复审全过。最终evidence/P4/phase-report.md、validation-reviewed.json。
- 集成codex/p3-p5-longrun，最近核验远程f020efa，随后J5准备dda13f4/14b8272/1cd1615/cfd6619已本地集成，需推送核验。

## 当前owner和下一动作

1. root独占共享配置/契约/平台/STATUS/MAIN/前端/部署fake；池上限可配置默认8、容量显式32，冻结docs/openapi资源已编译，补测试提交。修HEAD/OPTIONS、真实用量与指标。
2. acceptance_matrix原j5-01 worktree：full03实际186HTTP/198步、25表delta，strict FAIL；本地fake chunked等未提交，先定位用量差异并补6引用真实finish外层事务回滚helper，申请PG窗口。不能填黄金结果。
3. capacity原j5-02：52a6e96/e589e1e待集成，TCP/故障/队列脚本纯12Python+2Java通过；真实PG/故障/72分钟容量均未执行，等核心门禁与root窗口。
4. operations原j5-03：e601431已集成，后续二片未提交。P4镜像源码label f020efa，backend c6f4799e、frontend a862bcc，jar54b12585，archives保留；Docker socket当前消失，未重启旧环境。恢复执行器/guard/runbook纯准备继续，实际场景待J5-01核心/J5-02门禁。root写fake，worker不动共享Compose。
5. 浏览器真实多角色11场景未执行，root用browser-harness、自有Chrome profile、独立p5_ui库。J5-04交付准备与最终P5报告/双轴/全量/remoteSHA一致仍待做。

## 资源

- PG127.0.0.1:55481/postgres，p1_java，期望主checkout/.migration-artifacts/p1/pgdata；密码只解析主checkout/.migration-artifacts/p1/local-credentials.txt且不输出，四项DPDK_TEST_PG_*显式。建删/DDL先身份预检；不FORCE、不删恢复资产。full03已释放库/进程。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21；Python依赖主checkout/.migration-artifacts/p0/venv/bin/python；Node24.18.1。
- 新Colima java-u12 4CPU/4GiB/20GiB，context colima-java-u12，socket /Users/fsy/.colima/java-u12/docker.sock，daemonID8f66e5ea-9c07-4379-b27d-7a5759cc2cd3。旧dpdk停止，全局context不改；当前socket不存在，实际栈未启动。
- 上份P4 MAIN已归档archive/MAIN-U12-P4-integration-20261026.md。不得删除任何worktree、失败证据、镜像或恢复资产。
