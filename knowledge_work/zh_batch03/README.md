# 中文教学资料：第三批 TLS 专题

状态：**单篇待人工审核；未入库、未发布**。依据任务书 `../tasks/zh_batch03_taskbook.md` 执行；不宣称问答质量改善。

## 阅读入口

- [TLS、HTTPS、SNI 与证书：模块职责和可观测边界](drafts/tls_module_boundaries.md) · [逐项证据](evidence/tls_module_boundaries.md)
- [已有资料盘点与本批增量](inventory.md) · [机器可读清单](manifest.json)
- [编写者自审](reviews/self_review.md) · [独立 Agent 静态审核](reviews/independent_review_20260919.md) · [人工审核清单](reviews/review_checklist.md)
- [检查结果](checks/validation.md) · [交付说明](delivery.md)

前两批：[zh_pilot](../zh_pilot/README.md)（两篇，rev 2）、[zh_batch02](../zh_batch02/README.md)（四篇，rev 2）。

## 来源与工作区

主项目固定版本 `20f882691ac03998e0e148e0a66041227cd3f751`（master）。开工时主仓库 HEAD 恰为该提交，工作树只有未跟踪的 `ai_chat_assistant/`；
助手仓库 `a8d0fbec7667a0580a8c6b7fde838d1b7aa0012d`（main），工作树只有未跟踪的 `knowledge_work/`。
22 个来源文件的**工作树字节与固定提交逐一相同**，TLS 簇无变化，因此沿用同一版本：未整体换版、未混用版本、
未把未提交源码纳入依据。

`snapshots/` 只含本批必要的 22 个已提交文件，由其内部 `.gitignore`（`*` + `!.gitignore`）排除。
实际设置在 `backend/` 工作目录解析；已核对 27 个 include 入口与 211 个候选文件，本批工作区不在扫描范围内。

恢复快照（只读本地 Git 对象，不访问远端，不运行其中代码），从助手仓库根目录执行：

```bash
python3 - <<'INNER'
from pathlib import Path
import json, subprocess
base = Path('knowledge_work/zh_batch03')
m = json.loads((base / 'manifest.json').read_text())
repo = Path(m['source_repo'])
for item in m['source_files']:
    data = subprocess.check_output(['git', '-C', str(repo), 'show',
                                    m['source_commit'] + ':' + item['path']])
    target = base / item['snapshot']
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
INNER
```

换主机时只改 `repo` 变量指向拥有该 commit 的仓库并在副本中记录，不能暗改来源提交。

## 复核命令

只导入设置、扫描器与纯切分器；不建库、不 ingest、不调模型（脚本内阻断 socket 建连）：

```bash
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_batch03/checks/check_documents.py
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_batch03/checks/check_documents.py --preview-json
python3 knowledge_work/zh_batch03/checks/build_evidence.py
```

证据表由清单单向生成：改事实请改 `manifest.json` 后重跑 `build_evidence.py`。
`checks/build_manifest.py` 只在建档时运行过一次（它把"报告全部失效引用"的校验固化了下来），
**不要重跑**，否则会覆盖之后对清单的修订。

## 自动检查不能证明什么

检查覆盖结构、来源一致性、行范围与符号定位、跨篇与跨批引用可解析、链接、扫描隔离、内容筛查、
前两批文件未被覆盖与静态切分形态；**不能**证明源码解释语义正确，也**不等于**运行验收或安全审计。
本篇的自审是**编写者自审**；rev 2 另经一次**独立 Agent 静态审核**（新上下文重读快照，见上链）并落实其意见，
但两者都不等于人工审核。人工审核完成前不得复制到
`knowledge/guides/` 或其他被扫描目录；入库、A/B、模型预算、第四批扩写都不是本批的自动下一步。

任务书第八节的四条专项红线（"未实现"与"技术上不可得"不得互写等）自动检查抓不到，
只能靠[人工审核清单](reviews/review_checklist.md) 兜。
