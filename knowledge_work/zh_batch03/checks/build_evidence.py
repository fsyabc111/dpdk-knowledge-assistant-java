#!/usr/bin/env python3
"""从 manifest.json 重建 zh_batch03 的 evidence/*.md，避免正文事实表与清单手工漂移。

只读 manifest 与本批快照，写 evidence/ 下两个 Markdown；不访问网络、不读主仓库工作树。
用法（仓库根目录）：python3 knowledge_work/zh_batch03/checks/build_evidence.py
"""
from pathlib import Path
import json

BASE = Path(__file__).resolve().parents[1]
UNCERTAIN = {
    'tls_module_boundaries': [
        '- 任何 TLS 报文的实际解析结果：未构造报文、未加载 `.so`、未运行，本篇只述代码条件。',
        '- ClientHello 跨段时三条通路的漏检比例、重组上限的真实影响：未测量。',
        '- 证书旁路的临时结构未填 `ip_version` 是否有意取舍：代码中未找到说明；若确认非有意，属实现侧待办，不在资料批修改。',
        '- JA3 与 `tls_parser.c` 在 Session ID 跳变上的检查差异是否构成越界读、影响哪些输入：需单独的代码评审与安全审计批次，本篇不作判断。',
        '- MongoDB 同步写与 ch_event 异步通路在真实负载下的失败与丢弃量级：未验证。',
        '- `tls_parser.c` 编入 5 个 `.so`（另有 site_ranking 的 `.o` 副本）后的实际链接结果：未执行 make 验证。',
        '- `mongo_insert_certificate` 与 `engine_log_event` 在何种条件下真正落库、失败与阻塞语义如何：两者实现不在本批来源清单内，本批未核，TLS-012 只述调用点。',
        '- 候选槽是否参与 `ext_locked` 锁定仲裁的完整规则：`session/session.c` 不在本批来源清单内，本篇只按各插件调用哪个 setter 陈述（详见第二批 SF-007/SF-008）。',
        '- ECH/DoT 判据的覆盖率与误报率、DoH 是否需要另建检测路径：未核验，本篇只说字段被硬编码为 0。',
    ],
}
FOOTER = ['', '## 待确认清单', '', '下列内容本批**未**得出结论，正文也不得依赖其成立：']


def render_refs(commit: str, references) -> str:
    parts = []
    for ref in references:
        span = (f"L{ref['start_line']}" if ref['start_line'] == ref['end_line']
                else f"L{ref['start_line']}–L{ref['end_line']}")
        # 相对 evidence/ 目录的仓内路径：不写绝对路径，避免把本机目录结构固化进资料包
        target = f"../snapshots/dpdk/{commit}/{ref['path']}"
        parts.append(f"[{Path(ref['path']).name}]({target}) {span}，`{ref['symbol']}`")
    return '；'.join(parts)


def build():
    manifest = json.loads((BASE / 'manifest.json').read_text())
    commit = manifest['source_commit']
    for doc in manifest['documents']:
        evidence = BASE / doc['evidence_path']
        lines = [f"# {doc['topic']}：事实证据表", "",
                 f"固定源码版本：`{commit}`。下面行号只对应该快照。"
                 "核查类型：静态阅读与编写者自审，rev 2 另按独立 Agent 静态审核修订；"
                 "**不是人工审核，也不是运行测试**。", "",
                 "快照默认不纳入 Git（见 `snapshots/.gitignore`）；缺失时按 README 的导出命令从本地 Git 对象恢复。"
                 "定位文本命中只证明“该范围内出现该字符串”，不代替对分支语义的审核。", "",
                 "| 事实编号 | 正文位置 | 项目事实 | 依据（版本同上） | 核查说明 | 状态 | 与旧说明/推断的差异 |",
                 "| --- | --- | --- | --- | --- | --- | --- |"]
        for fact in doc['facts']:
            lines.append(f"| {fact['id']} | {fact['section']} | {fact['claim']} | "
                         f"{render_refs(commit, fact['references'])} | "
                         f"{fact['reasoning']} | {fact['status']} | {fact['difference']} |")
        lines += FOOTER + UNCERTAIN[Path(doc['document_path']).stem] + [
            '', '## 与首批事实的关系', '',
            '首批 28 项 PL/PF 事实经独立静态审核后修订到 rev 2；第二批 45 项 SL/SF/DN/DR 事实见 `../zh_batch02/`。'
            '本篇只引用其结论、不重复其内容。rev 2 已落实独立 Agent 静态审核'
            '（`../reviews/independent_review_20260919.md`）的意见，本篇仍**待人工审核**。']
        evidence.write_text('\n'.join(lines) + '\n')
        print('wrote', doc['evidence_path'], len(doc['facts']), 'facts')


if __name__ == '__main__':
    build()
