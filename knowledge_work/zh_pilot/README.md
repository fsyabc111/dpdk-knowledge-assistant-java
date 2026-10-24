# 中文教学资料：两篇样稿与证据包

状态：**样稿与证据包，待人工审核；未入库**。
适用读者：懂 C 与网络基础、初识本项目的学生。写作依据固定主项目源码，未修改共享规划或现有知识资料。

## 阅读入口

- [插件接口与生命周期](drafts/plugin_lifecycle.md) · [逐项证据](evidence/plugin_lifecycle.md)
- [报文处理主流程](drafts/packet_flow.md) · [逐项证据](evidence/packet_flow.md)
- [已有资料盘点](inventory.md) · [机器可读清单](manifest.json)
- [编写者自审](reviews/self_review.md) · [人工审核清单](reviews/review_checklist.md)
- [检查结果](checks/validation.md) · [交付说明](delivery.md)

## 来源与工作区

主项目固定版本：`20f882691ac03998e0e148e0a66041227cd3f751`，分支 master；助手启动版本：`a8d0fbec7667a0580a8c6b7fde838d1b7aa0012d`，分支 main。执行前助手工作区干净；主项目仅有未跟踪的 `ai_chat_assistant/`，它是独立仓库，不视为源码差异。

实际设置在 backend 工作目录解析。已检查 27 个 include 入口及实际候选文件，草稿、证据和快照均不在扫描范围。配置以后变化须重跑检查，不能因为目录叫 knowledge_work 就永远认定安全。

来源清单见 [source_baseline.json](checks/source_baseline.json)。`snapshots/` 只包含本批必要的 22 个已提交文件，并由其内部 `.gitignore` 排除，避免重复提交主项目源码。证据链接依赖这些本地快照；共享文件包时可用以下命令从已有本地 Git 对象恢复，不访问远端，不运行其中代码。

从助手仓库根目录执行：

```bash
python3 - <<'PY'
from pathlib import Path
import json, subprocess
base = Path('knowledge_work/zh_pilot')
manifest = json.loads((base / 'manifest.json').read_text())
repo = Path(manifest['source_repo'])
for item in manifest['source_files']:
    data = subprocess.check_output(['git', '-C', str(repo), 'show', manifest['source_commit'] + ':' + item['path']])
    target = base / item['snapshot']
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
PY
```

如果换主机，只修改命令中的 repo 本地变量指向拥有该 commit 的仓库；不能用当前工作树替代固定版本。检查脚本目前按 manifest 中本机仓库位置读取，换主机需在副本中明确迁移路径并记录，不能暗改来源提交。

## 复核命令

从助手仓库根目录运行（仅导入设置、扫描器与纯切分器；不初始化数据库、不执行 ingest、不调用模型）：

```bash
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_pilot/checks/check_documents.py
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_pilot/checks/check_documents.py --preview-json
```

检查脚本只读，JSON 输出到 stdout。`chunk_preview.json` 是本次执行输出保存的预览，不会由普通检查自动覆盖。脚本阻断 socket 建连，仍应使用现有受信任的依赖环境。

自动检查只证明结构、路径、定位、切分和隔离，不能证明源码解释语义正确。自审不是独立审核；人工确认之前不得复制至 `knowledge/guides/`。入库、A/B、模型预算、后续扩写都不是本批自动下一步。
