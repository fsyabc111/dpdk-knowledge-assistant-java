# J5-03 准备与实际演练入口

完整步骤及边界见 `docs/java-migration/cutover-runbook.md`。当前仅准备完成，实际窗口未开放。

| 入口 | 作用 | 默认副作用 |
|---|---|---|
| snapshot.py | 全source/include合成源生成 | 只写显式新输出目录 |
| prepare.py | 新round、合成源、新口令env、TLS | 只写gitignored目录；零Docker/PG |
| guard.py | 静态env/owned/project/override及实际daemon身份守卫 | 默认实际只读inspect/info；--static零daemon |
| images.py | 实际image ID/源码label/save/jar或静态资源摘要 | 新profile的镜像只读/本地归档；无runtime容器 |
| override.py | allowlisted owned JSON overlay | 只写新的600本地文件 |
| docker_pg.py | owned PG创建、合成0013、备份/恢复/哨兵只读断言 | 仅显式执行器调用时连接本round PG |
| backup_cli.py | 默认dry-run，--apply备份与TOC断言 | 默认零PG/daemon |
| probes.py | localhost TLS/API/身份/持久消息/引用/SSE断言 | 实际阶段才请求 |
| rehearse.py | plan/cold/upgrade/backup_restore/rollback | 默认plan零Docker/PG；--execute才执行 |

```bash
python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v
python3 -m py_compile acceptance/deployment/*.py
```

标准库Python只是隔离验收工具，不导入旧Python主后端。原项目、旧profile、生产库和真实上游保持只读/未调用。所有恢复资产保留，不提供清卷或DROP入口。

恢复数据证明由business_proof.py动态枚举全部public表，以单一REPEATABLE READ READ ONLY快照生成每表完整行多重集SHA256和列结构SHA256，比较同count错grade等反例；临时输入表只比较列结构且恢复0行。只输出摘要/计数，不输出原始行；不把该证明扩大为sequence/约束/索引完整catalog等价。摘要SQL已在主授权的native独立3表窗口验证正反例；实际Docker/dump/恢复仍待主窗口。
