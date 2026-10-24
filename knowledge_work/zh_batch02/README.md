# 中文教学资料：第二批四篇（会话两篇 / DNS 两篇）

状态：**待人工审核；未入库、未发布**。本批不宣称问答质量已经改善，也不改变共享规划或现有知识资料。

rev 2 变更：按用户验收决定，把原来的两篇各拆两篇，控制单篇长度；事实内容与源码依据未变，仅重新编号并归入新章节
（`SL`/`SF`/`DN`/`DR` 四个前缀，合计仍为 45 项）。旧文件 `session_lifecycle.md`、`dns_implementation.md` 已被四篇取代。

## 阅读入口

会话主题（两篇互链，建议按序读）：

- [会话键与读写路径：一条流怎样被登记和计数](drafts/session_key_and_lookup.md) · [事实表](evidence/session_key_and_lookup.md)（SL-001…SL-008）
- [会话过期与回收：两种清扫、回调与扩展释放](drafts/session_expiry_and_release.md) · [事实表](evidence/session_expiry_and_release.md)（SF-001…SF-013）

DNS 主题（两篇互链，建议按序读）：

- [DNS 判据与解析：从 UDP 53 到域名还原](drafts/dns_detection_and_parsing.md) · [事实表](evidence/dns_detection_and_parsing.md)（DN-001…DN-013）
- [DNS 结果与返回值：结果落在哪里、失败怎么返回](drafts/dns_results_and_returns.md) · [事实表](evidence/dns_results_and_returns.md)（DR-001…DR-011）

包级文件：[已有资料盘点](inventory.md) · [机器可读清单](manifest.json) · [编写者自审](reviews/self_review.md) ·
[人工审核清单](reviews/review_checklist.md) · [检查结果](checks/validation.md) · [交付说明](delivery.md)

首批资料见 [../zh_pilot/README.md](../zh_pilot/README.md)；对首批的独立审核见
[independent_review_20260918.md](../zh_pilot/reviews/independent_review_20260918.md)。

## 来源与工作区

主项目固定版本沿用首批：`20f882691ac03998e0e148e0a66041227cd3f751`（分支 master）。助手启动版本
`a8d0fbec7667a0580a8c6b7fde838d1b7aa0012d`（分支 main）。采集本批基线时主仓库工作树只有未跟踪的
`ai_chat_assistant/`（独立仓库），助手仓库只有未跟踪的 `knowledge_work/`。

版本比较结果：主仓库 HEAD 恰等于首批固定提交，本批 17 个来源文件的**工作树字节与固定提交逐一相同**，
相关调用关系与协议行为没有变化，因此继续沿用同一版本，不整体升级、不混用不同版本、不把未提交源码纳入依据。

`snapshots/` 只含本批必要的 18 个已提交文件，由其内部 `.gitignore`（`*` + `!.gitignore`）排除，
不重复提交主项目源码。实际设置在 `backend/` 工作目录解析；已核对 27 个 include 入口与 211 个实际候选文件，
本批的草稿、证据、检查产物和快照均不在扫描范围内。配置以后变化须重跑检查。

### 恢复快照

从助手仓库根目录执行，只读本地 Git 对象，不访问远端、不运行其中代码：

```bash
python3 - <<'PY'
from pathlib import Path
import json, subprocess
base = Path('knowledge_work/zh_batch02')
m = json.loads((base / 'manifest.json').read_text())
repo = Path(m['source_repo'])
for item in m['source_files']:
    data = subprocess.check_output(['git', '-C', str(repo), 'show',
                                    m['source_commit'] + ':' + item['path']])
    target = base / item['snapshot']
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
PY
```

换主机时只改 `repo` 变量指向拥有该 commit 的仓库并在副本中记录迁移路径；不能用当前工作树替代固定版本，
也不能暗改来源提交。检查脚本按 manifest 中的本机仓库位置读取。

## 复核命令

仅导入设置、扫描器与纯切分器；不初始化数据库、不 ingest、不调用模型（脚本内阻断 socket 建连）：

```bash
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_batch02/checks/check_documents.py
PYTHONDONTWRITEBYTECODE=1 backend/.venv/bin/python knowledge_work/zh_batch02/checks/check_documents.py --preview-json
```

脚本只读，结果写 stdout。`checks/validation_result.json` 与 `checks/chunk_preview.json` 是本次执行输出的
存档副本，不会被普通检查自动覆盖。

两份证据表由清单生成，修改事实时改 `manifest.json` 后重跑：

```bash
python3 knowledge_work/zh_batch02/checks/build_evidence.py
```

检查覆盖范围包括：必需章节与源码版本、45 项事实（SL 8 + SF 13 + DN 13 + DR 11）的行范围与符号定位、正文与清单的编号对应、
跨篇与跨批引用可解析、本地链接、扫描隔离、内容筛查、本机绝对路径外泄，以及首批文件与本批文件未被覆盖。

## 自动检查不能证明什么

检查只覆盖结构、来源一致性、行范围与符号定位、本地链接、扫描隔离、内容筛查和静态切分形态。
它**不能**证明源码解释语义正确，也**不等于**运行验收或安全审计。

本批两篇的自审是**编写者自审**，不称为独立审核；首批两篇的独立静态审核另见上。两批共四篇资料在完成
人工审核之前，都不得复制到 `knowledge/guides/` 或其他被扫描的目录。入库、A/B 对照、模型预算、
第三批扩写都不是本批的自动下一步。
