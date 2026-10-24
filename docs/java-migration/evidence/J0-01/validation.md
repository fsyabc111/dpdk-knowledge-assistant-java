# J0-01 可提交验证摘要

- 工作区：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；共享分支 `codex/p0-baseline-oracle`。
- 冻结提交：`80b29135faddd16d5e39fdbf0633291ea6a20489`。原 manifest SHA-256：`29dc72e7f19c949f9d70b642e3e085a98bd26b1ca2a320fc0be43b5461d7e60c`。
- 方式：Python AST/SQL文本/哈希静态检查，不导入 app、FastAPI、数据库驱动或配置，不读取旧根目录/.env，不发网络请求。

## 实际执行

| 命令（工作目录均为仓库根） | 环境/退出码 | 断言与结果 | 原始输出 |
|---|---|---|---|
| `python3 migration/baseline/inventory.py --write` 与不带 `--write` | 系统Python3.9，0 | 原manifest 378文件hash/大小/可执行模式一致；AGENTS/README/.gitignore三映射正确；66路由/29表/103设置/63模型无遗漏、重复或无owner | 初次工具输出；最终复跑见下行 |
| `.migration-artifacts/p0/venv/bin/python migration/baseline/inventory.py` | Python3.11.15，0 | 上述静态集合完全重现；96个env模板键、4项YAML键、19项需求和17项import根映射齐全；未知动态注册=0 | `.migration-artifacts/p0/j0-01/inventory-final.log` |
| `python3 -m unittest discover -s migration/baseline -p 'test_*.py' -v` | Python3.9，0 | 5项护栏测试通过：真基线、不导入app、漏/多/重复/无owner拒绝、未知动态/未装配路由拒绝、隐藏/metrics/docs/HEAD分类 | `.migration-artifacts/p0/j0-01/static-validation.log` |
| 同上，用专用venv Python执行 | Python3.11.15，0 | 5项同样通过 | `.migration-artifacts/p0/j0-01/static-validation-python311.log` |
| 合成静态制品检查（stdlib JSON/AST递归校验） | Python3.9，0 | 9份JSON可解析、2093处源码位置均存在/行号合法；高置信私钥/token模式命中0 | `artifact-check.json` |
| `git diff --exit-code -- backend frontend` | Git，0 | 冻结Python/前端原件未被修改 | 工具输出为空 |

`coverage-report.json` 是枚举与映射覆盖的机器证据。`artifact-check.json` 的JSON数量不含该报告自身（其生成前统计）。秘密扫描为高置信模式检查，不声称穷尽所有秘密。

## 曾失败但已修复的护栏实现

- 系统Python3.9没有 `sys.stdlib_module_names`，初次枚举退出1；已增加stdlib/lib-dynload静态回退，3.9/3.11现均通过。
- Python3.9与3.11的 `ast.unparse` 对完整for循环元组括号格式不同，首次3.11一致性检查退出2；配置helper改用原始source segment，跨版本再现通过。没有改源码或放宽内容哈希。

## 实证边界

- J0-01未启动Python应用、未连接任何PG、未调用真实或替身模型/MCP、未运行评测/性能/恢复。动态证据由J0-02/性能owner提供，后续再引用确切fixture/测量ID。
- 29项是完整生产表**命名与去向**（含动态LI与alembic元数据），不是29表实际catalog全量验证。动态LI列/索引、条件zhparser和真实旧0013结构均未验证。
- 原外部系统、DPDK/harness、生产PG、真实模型/embedding可用性全部未验证；未推送、未P1实施、未做生产操作。
- 待决项：COMP-003的harness同job_id幂等证据/用户批准差异；COMP-010首次/重放分开fixture；阈值保持待J0-02测量。它们没有被伪装为通过。
