# 容器许可原始字节归档（P4/锁定base实物）

索引：docs/java-migration/evidence/J5-04/container-license-text-index.json；摘要：container-license-text-summary.json。读取之前container-inventory-p4.json所列7个不可变image与1390个许可/NOTICE/copyright、JRE legal及release识别元数据路径，不扫描环境、任务输入、挂载源或凭据。每条保留image ID、原文件路径、原字节SHA256、大小及sha256/<hash>.txt内容对象；所有原文hash与既有实物库存逐项相等。

1390条按镜像计记录去重为307个container对象、2542341原字节；与Maven/npm归档共用内容对象库，交叉重复不另复制。保留原编码、换行及空白，不把原始文本规范化。完整版本/OS包归属继续引用之前container inventory，本文不把许可原文等同组件已全部法律审查。

采集使用container_license_bundle.py：每image前endpoint/daemonID/Name guard及image ID复核；docker --context colima-java-u12 run --rm -i --pull never --network none --read-only --user65534:65534 --cap-drop ALL/no-new-privileges，限制CPU/memory/pids，entrypoint=/bin/tar，stdin仅输入既有库存路径。无端口/挂载，不运行Java/Node/npm/nginx/PostgreSQL或包代码，也不采env。原文tar仅在内存读取，短容器退出自动清理，没有Docker stack/应用/PG服务。

实际完成后新context ps为空，旧dpdk Stopped、全局context仍colima-dpdk-ext；worker未启动/停止profile。首次采集因缺stdin -i得到空tar，路径完整性守卫拒绝；修正后1390/1390才写成功索引，未将失败当通过。

Node22.23.4与pgvector0.8.4官方tag许可原文已由root另片补入license-text-bundle-index.json（含immutable commit及原字节hash），本片不覆盖其后续证据或旧summary。当前container文本补齐此前仅有hash的路径；镜像未包含的文本不冒充实物存在。缺失平台npm缓存、无文本JAR/包、完整再分发NOTICE组合、最终P5 candidate镜像与法律/源代码义务核对仍需独立闭合。

复采仅在获准环境准备窗口、guard变量明确且新profile已由root恢复时执行；正式容量CPU独占时不运行。既有image/save/业务恢复资产保持不变。本次许可读取及P4归档重验不能替代J5-03冷启/升级/恢复/回退四业务场景。
