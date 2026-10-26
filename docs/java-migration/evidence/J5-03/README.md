# J5-03 准备证据（非实际演练）

源基线f020efa；准备首片e601431；二片提交见本卡分支。当前P5严格矩阵FAIL、正式容量未跑，实际冷启/升级/恢复/回退未获主Agent队列窗口，全部pending。

| 实际执行 | 结果 | 不代表 |
|---|---|---|
| Python unittest discover acceptance/deployment | 14/14通过，零skip；guard旧context/env/daemon、全source/include、owned overlay/DB、空库恢复策略、dump TOC与哨兵SQL | 未连接PG，不能证明真实恢复 |
| py_compile全部验收脚本、bash -n三个授权shell | 通过 | 未执行shell运行场景 |
| prepare生成新口令env/TLS/全合成知识快照 | 10 source/27 include/27合成文件；env/key0600；全Git忽略 | 没有复制原项目源或调用真实模型 |
| 新context只读identity guard及静态Compose展开 | 首轮已通过；恢复后再次核对见交接 | 非冷启、非部署 |
| P4两个Dockerfile build（锁定已拉取base digest） | 实际成功；后端/前端image与归档/构建内容hash见p4-images.json | build不是实际运行验收 |
| 前端构建红绿 | 首次因专属dockerignore排除nginx配置失败；放行该配置后构建成功 | 未运行nginx/TLS |
| images.py inspect/save及离线归档摘要 | 两P4不可变source label/ID/归档保留，backend.jar实际摘要已提取 | 不仅换tag，也没有create/run容器 |
| rehearse.py plan | 明确pending及候选image依赖缺失，零Docker/PG动作 | 不允许推进场景状态 |

真实P4源码f020efa951b816cc3315f169c483af0998875800；backend.jar SHA256=54b125854754577f861d018bd68d3b745455d046d09a162c0d554bc78eda39db。源manifest/配置/镜像/jar/知识版本分别记录。原始构建输出、归档、env、私钥与运行状态均在ignore `.migration-artifacts/j5-03/`，不提交。

二片新增owned round project、600 allowlisted JSON overlay、Docker PG新库身份/所有权、合成0013、custom dump只排输入数据、空库实际restore命令及业务/哨兵断言执行器。它们当前只通过准备策略测试；待J5-01核心/J5-02门禁及冻结候选后由主Agent开窗实际运行并追加真实结果，失败和未执行不计通过。
