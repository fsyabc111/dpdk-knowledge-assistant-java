# README 镜像 OCI 阻断窄复核

2026-10-28。固定 BASE=`8ed2189a9e8adea70f35e8baba1dfec729fb0e78`、TARGET=`bf38cfe81b659e683a28c6dfad8bb9dba713f41f`，已核实；仅审新增归档身份校验及两处剩余文案。生产源码仍按主Agent冻结范围，不借移动HEAD推论。未构建、测试、PG、HTTP或Docker；未解压/hash真实大层或重算完整归档SHA。

## 原 P2 关闭情况

**静态关闭，未发现本增量新增的真实规范阻断。** `acceptance/deployment/images.py:26-77`不再把所有image ID等同config摘要：

- 无index.json的legacy分支仍严格要求config SHA等于实际ID。
- OCI分支要求index.json中唯一根descriptor与实际ID匹配；每个已选metadata对象检查blob digest、size、schema和index/manifest mediaType；归档引用须唯一普通文件，metadata有4MiB上限、索引深度有界。
- 按inspect的OS/architecture及已有variant筛选，目标平台可运行manifest必须唯一；非目标attestation不充作候选。manifest的config摘要、大小、平台与Docker兼容manifest.json的Config完全绑定，Layers路径/顺序也必须与OCI descriptor清单相等。
- 完整record路径`:105-117`随后仍核对压缩blob摘要与解压diffID，未用新metadata函数代替层完整性检查；既有不可变P4记录的稳定字段不符仍拒绝，未降低旧资产护栏。

新增`test_image_oci_chain.py`覆盖index-ID/config-ID分离、直接manifest-ID、根/child/config篡改、错误平台、错误layer路径/大小、同大小blob篡改；合成小gzip完整record仍校验artifact。worker登记6项新增/全36纯测试通过，本复核仅读其代码及交接，不复跑或把这些模拟测试当作Docker实物通过。

## 独立只读的既有 P4 元数据核对

读取主worktree `.migration-artifacts/worktrees/j5-03/.migration-artifacts/j5-03/build/p4-*.image.tar` 的tar headers、index.json、manifest.json、目标index/manifest及小config blob，没有读取layer payload：

| 归档 | 实际根ID前缀 | 目标manifest前缀 | 根/manifest声明大小与实际大小 | config绑定 | layer清单 |
|---|---|---|---|---|---|
| backend | c6f4799e40d5 | 4934c5659b8f | 856/856、2003/2003 | 124ecf351b81…；10271/10271字节；linux/arm64 | 9层，路径/顺序相等 |
| frontend | a862bcc15031 | 8362b7ba9f77 | 856/856、2377/2377 | 773ae2d0fb40…；14195/14195字节；linux/arm64 | 11层，路径/顺序相等 |

上表根与manifest的内容SHA均等于各自descriptor，根ID与既有`p4-images.json`一致；config摘要与原复核记录一致。这证明原来误拒的两份containerd归档在正确身份域可以建立链，而不是简单跳过config检查。**不证明真实layer/blob内容或整档摘要当前已重验，也不证明Docker save/load、运行与回退成功。**

## 文案与后续门禁

`cutover-runbook.md:127`及`delivery/OPERATIONS.md:39`已补无state显式`--base`/`--base --apply`、不得借此选择已有upgrade/restore state、apply核对实际backend以及dry-run未现场核验的限制。runbook`:136`已改为fixedQueries-only，单配真实embedding不再被写成CLI正例。原两项窄文案问题关闭。

实际窗口仍须完成：新owned round的P4/candidate四角色真实save/load/登记与完整压缩层/解压diffID/整档SHA核验；base/upgrade/restore备份目标与运行backend一致性；cold→全源索引→0013→另一空库恢复→P4应用回退。失败和旧资产保留，不覆盖原档，不继承模拟测试结论。

**结论：原OCI P2代码阻断及剩余文案问题静态关闭；独立接手实际复现、完整实物归档及部署门禁继续pending。** 仅新增本报告，不修业务、不提交，不代J5-04实际验收或用户/live/生产放行。
