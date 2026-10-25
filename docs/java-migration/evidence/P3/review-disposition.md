# P3双轴审查处置

原审查快照3718d21，原自动验证585/585；该快照因以下4阻断暂不放行。两轴保持独立，修复只含阻断及必要依赖。

| 轴/ID | 问题 | 处置与责任 | 实测/复审状态 |
|---|---|---|---|
| 规格SP-P3-01/P1 | 产品token预算检查晚于工具执行 | 主Agent14d756d：每次reply累计后、返回图层前拦截 | worker补真实首轮/跨轮回归中；复审待做 |
| 规格SP-P3-02/P2 | 真实检索丢会话语境 | 主Agent14d756d：SearchInput兼容扩展、QueryRewritePort上下文重载与完整调用链 | worker补真实历史→追问消解→引用回归中；复审待做 |
| 规范ST-P3-01/P2 | results重复包装，画像丢失败用例 | chat owner：Agent只传原始数组，TaskGradeLedger单点包装来源 | 单卡18/18通过，待集成提交与全量；复审待做 |
| 规范ST-P3-02/P2 | chat直接SQL越过grading模块 | chat owner：grading公开画像/统计服务，chat仅委托；无产品消费者的非task写入明确拒绝 | 同上，待集成与复审 |

非阻断possible Duplicated Code（工具schema/description重复）登记后续维护项；P3冻结后不为此扩大重构。

失败证据和原审查报告保留。修复版需重新绑定源码SHA、集成验证和构建指纹，再由原两位只读审查worker分别复核，不把局部绿色代替关闭证据。P4仍未放行。
