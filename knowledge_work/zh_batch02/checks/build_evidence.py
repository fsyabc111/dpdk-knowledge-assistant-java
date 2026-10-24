#!/usr/bin/env python3
"""从 manifest.json 重建 evidence/*.md，避免正文事实表与清单手工漂移。

只读 manifest 与本批快照，写 evidence/ 下两个 Markdown；不访问网络、不读主仓库工作树。
用法（仓库根目录）：python3 knowledge_work/zh_batch02/checks/build_evidence.py
"""
from pathlib import Path
import json

BASE = Path(__file__).resolve().parents[1]
UNCERTAIN = {
    'session_key_and_lookup': [
        '- 短包导致端口保持 0 后究竟会合并多少条流：未构造报文、未统计。',
        '- RSS 是否把同一五元组的双向报文稳定分到同一队列：未读硬件配置、未测量，正文仅登记为排查方向。',
        '- `session/README.md` 建议的哈希表大小与真实负载下的桶长分布：未测量。',
        '- 会话层默认参数在真实流量下的内存占用：未计算、未运行。',
    ],
    'session_expiry_and_release': [
        '- 清扫预算（2048 桶 / 250 微秒）的实际效果与完整清扫时限：未测量，正文不作性能保证。',
        '- `handle_expired_session_for_profiles` 所调用的裁决引擎与 IP 画像接口能否并发调用：未逐函数核验。',
        '- 关闭时未过期会话不被补记的实际业务影响：未验证，正文只描述代码路径。',
        '- worker 的 `CLOCK_MONOTONIC` 与会话的 `time(NULL)` 在真实系统上是否产生可见偏差：未验证。',
        '- 默认 4194304 桶按 worker 数整除后的创建失败概率：未计算、未运行。',
    ],
    'dns_detection_and_parsing': [
        '- 任何真实 DNS 报文的解析结果：未构造报文、未加载 `.so`、未运行插件。',
        '- 各长度与深度阈值在真实流量下的命中率：未统计。',
        '- 加密 DNS（DoH/DoT/ECH）的判据与检测完整性：本批只定位到 `engine_tls_infra.cpp` 存在标记路径，未核验其条件。',
        '- TCP 承载 DNS 是否由其他插件处理：未在其他插件中查证，正文只说明本插件不处理。',
    ],
    'dns_results_and_returns': [
        '- `ch_event_enqueue` 丢弃计数在真实负载下的量级、事件最终是否入库：未验证。',
        '- 会话扩展被其他插件接管后的下游可见性影响：未跟踪消费方代码。',
        '- `new` 失败路径在本编译配置下的可达性：按 C++ 通用行为推断，未实测。',
        '- `dns_ext_same_pending_request` 停用是临时权衡还是长期设计：只有源码注释支撑，属设计意图，未确认。',
        '- 缓存三个上限的合理性与内存占用：未测量。',
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
                 "核查类型：静态阅读与编写者自审；**不是独立审核，也不是运行测试**。", "",
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
            '首批 25 项 PL/PF 事实已经独立静态审核（见 `../zh_pilot/reviews/independent_review_20260918.md`）。'
            '本批在其之上补会话与 DNS 两段，不重复其结论；两篇首批样稿仍为**待人工审核**。']
        evidence.write_text('\n'.join(lines) + '\n')
        print('wrote', doc['evidence_path'], len(doc['facts']), 'facts')


if __name__ == '__main__':
    build()
