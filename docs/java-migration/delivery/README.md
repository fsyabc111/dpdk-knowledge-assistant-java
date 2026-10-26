# J5-04 交付准备包

这是交付准备，尚未代签P5工程门禁、用户验收或生产切换。当前已知P4通过；P5严格矩阵尚未通过、正式容量未执行，J5-03实际场景pending。主Agent最终按真实证据更新交付manifest；不能仅因为文件齐全而改成验收通过。

| 文件 | 用途与权威 |
|---|---|
| delivery-manifest.template.json | 最终交付清单模板；未知字段为null，验收维度明确分开 |
| DEPENDENCIES.md | 本轮实际Maven/npm依赖及许可证声明完整表；不替代容器OS清单 |
| generate_inventory.py | 根据实际resolved列表、artifact POM/JAR和lock生成机器可复核清单 |
| OPERATIONS.md | 运行/维护命令索引与恢复资产位置，引用已有切换手册 |
| HANDOVER_CHECKLIST.md | 接手验收步骤、通过条件、pending和保留边界 |
| ../evidence/J5-04/** | 依赖坐标/版本/hash/许可证出处、准备验证结果 |

依赖证据：Maven离线dependency:list实际100项（compile67/runtime4/test29）；npm lock213项（含dev/optional及平台变体，不等于最终bundle组件数）。全部取得许可证声明且本地Maven artifact存在。声明来自实际artifact POM/父POM或lock，不是从包名猜许可证。JAR中的LICENSE/NOTICE入口记录路径/hash；完整文本及发行义务未逐条核实，容器OS层SBOM/NOTICE未生成，状态明确pending。

输入pom和frontend lock与主集成5c8623c相同，SHA分别为a6a04b7167194ba9b1df38d04fb62b08c4e232ae0da7c4e109c92d91e4fb55cc、8a15f4eff452e1557df0e7fd932f2efa830d784c9c7a9cb016d95bab2a768a19。本清单绑定这些文件及实际resolved结果，P5候选若改变输入或解析结果必须重新生成，不能沿用旧清单宣称最终构建已覆盖。

源码自带根LICENSE为Apache License 2.0文本，保持原文件。第三方双许可证保留原声明，未代使用者选择授权路径；清单不表示所有组件统一Apache许可，也不代签法律兼容结论。
