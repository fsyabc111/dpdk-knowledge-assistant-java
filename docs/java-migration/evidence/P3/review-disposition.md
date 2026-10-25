# P3双轴审查处置

原审查快照3718d21、自动验证585/585曾因4阻断不放行。现修复快照5d0d6a3经全量591/591与两轴复审通过，以下4项均关闭。两轴保持独立，修复只含阻断及必要依赖。

| 轴/ID | 问题 | 处置与责任 | 实测/复审状态 |
|---|---|---|---|
| 规格SP-P3-01/P1 | 产品token预算检查晚于工具执行 | 主Agent14d756d：每次reply累计后、返回图层前拦截 | worker补真实首轮/跨轮回归中；复审待做 |
| 规格SP-P3-02/P2 | 真实检索丢会话语境 | 主Agent14d756d：SearchInput兼容扩展、QueryRewritePort上下文重载与完整调用链 | worker补真实历史→追问消解→引用回归中；复审待做 |
| 规范ST-P3-01/P2 | results重复包装，画像丢失败用例 | chat owner：Agent只传原始数组，TaskGradeLedger单点包装来源 | 单卡18/18通过，待集成提交与全量；复审待做 |
| 规范ST-P3-02/P2 | chat直接SQL越过grading模块 | chat owner：grading公开画像/统计服务，chat仅委托；无产品消费者的非task写入明确拒绝 | 同上，待集成与复审 |

非阻断possible Duplicated Code（工具schema/description重复）登记后续维护项；P3冻结后不为此扩大重构。

失败证据和原审查报告保留。最终指纹validation-reviewed.json，两轴复审review-standards-recheck.md/review-spec-recheck.md，均关闭原阻断。主Agent已据591全量与复审证据放行P4工程实施；用户验收仍独立未登记。表内单卡时点记录保留为修复过程，最终以本段为准。
