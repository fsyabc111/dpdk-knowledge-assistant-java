# 源码复制与完整性报告

- 复制时间：2026-10-24T21:36:06+08:00（Asia/Taipei）。
- 来源：`/Volumes/study/dpdk-runtime/workspace/dpdk/ai_chat_assistant`。
- 来源HEAD：`a47a1bc9d23c0a8e625a79ff7cfbd096dcb3071c`；包含当前未提交修改与未跟踪且未被ignore的源码/文档，不仅是该提交。
- 新目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
- 基线文件：378个，4,727,780字节；其中已跟踪318个、未跟踪60个。
- 来源逐文件摘要：[baseline-manifest.json](baseline-manifest.json)，SHA-256=`29dc72e7f19c949f9d70b642e3e085a98bd26b1ca2a320fc0be43b5461d7e60c`。

## 复制规则

从Git索引文件与未跟踪非忽略文件组成清单，复制实际工作区内容，保留可执行权限。保留LICENSE、测试、配置模板、当前未提交文档与知识草稿；草稿不表示已入索引。

排除 `.git` 及历史/远程配置、真实 `.env`、私钥、数据库/备份、日志、虚拟环境、node_modules、构建产物与运行缓存。规则优先于Git跟踪状态；本次候选清单额外排除0项。未读取真实.env内容。

保留 `.gitignore`/`.gitattributes` 与旧CI文件，因为它们是源码配置，不是Git仓库历史；旧CI只代表Python门禁，不代表Java流水线。没有创建Git仓库、远程、提交或推送。

外部DPDK完整主仓库与独立teaching_harness没有复制。现有知识资料内的局部来源快照保留，但不能替代完整运行依赖。缺少凭据、依赖安装和外部仓库的新副本不是可立即启动的完整部署。

## 对副本的有意变化

- 根AGENTS改为短迁移协作入口；原件逐字保存于 `reference/AGENTS.python.md`。
- 根README改为新仓库入口；原件逐字保存于 `reference/README.python.md`。
- .gitignore增加Java和临时证据排除规则，并将证书目录整目录忽略改为忽略其内容、显式放行 `gen_self_signed_cert.sh`，使新建仓库时不会漏提交该已复制脚本；原件逐字保存于 `reference/GITIGNORE.python`。
- 新增 `docs/java-migration/` 规划、任务卡、提示词、状态与交接模板。
- 其他基线文件保持逐字一致。参考快照内相对路径按原仓库根解释。

## 本次验证

- 全部378个基线文件逐项SHA-256相符，映射路径见manifest；可执行权限复制保留。
- 复制前后来源文件摘要与Git状态一致；原项目未改动。
- 目标父目录不属于其他Git仓库；副本内无.git文件/目录、无符号链接、无真实.env和运行依赖。
- 高置信度模式扫描覆盖私钥、常见provider key、GitHub token和JWT；候选为0。此扫描不等于穷尽所有潜在秘密，首次对外发布仍需按新仓库门禁检查。
- 规划结构、ID/链接/源入口检查见 [plan-check.json](plan-check.json)；独立审查见 [plan-review.md](evidence/PLANNING/plan-review.md)。
- 未运行Python/Java应用测试，未构建、启动服务、连接数据库、调用模型或执行评测。复制校验不能替代迁移验收。
