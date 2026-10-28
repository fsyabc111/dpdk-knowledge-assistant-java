# 第三方许可原始文本首批归档（非完整法律签认）

原始字节按SHA256存于sha256/<digest>.txt，重复内容只保存一次；未翻译、换行归一化或将多许可证擅自选一路。组件到原JAR entry/npm tar entry的精确映射、锁定坐标/版本/scope/integrity、来源和文本hash在../../evidence/J5-04/license-text-bundle-index.json，数量与缺项在license-text-bundle-summary.json。

Maven从声明清单对应的100个实际artifact核验完整hash，并提取其中已有LICENSE/NOTICE/COPYING原文；没有文本的18个JAR只保留POM声明，不能用通用许可证冒充版权/NOTICE齐全。npm直接只读本地cacache内精确lock integrity归档，核验sha512/版本后读取原许可文件，不运行npm安装或包脚本。文本是该P4准备输入的依赖集合，不是P5最终打包/树摇移除后的最终分发结论。

官方Node v22.23.4聚合LICENSE（主程序及外部维护内置库）和pgvector v0.8.4 LICENSE页面已核实：[Node官方固定tag](https://raw.githubusercontent.com/nodejs/node/v22.23.4/LICENSE)、[pgvector官方固定tag](https://raw.githubusercontent.com/pgvector/pgvector/v0.8.4/LICENSE)。本机读取原始文件遇到TLS EOF/SSL_ERROR_SYSCALL，未关闭TLS校验、未从页面重排文本伪称原始bytes。两份bytes及源tag immutable commit仍pending，精确URL和后续动作已列机器索引。

剩余边界：缓存缺失的optional平台包、超过轻量窗口4MiB限制的包、原包无独立许可证文件、JAR无内置NOTICE、Node/pgvector原文、OS/JRE容器内全文、完整再分发NOTICE组合/必要源代码义务与最终candidate实际组件均未闭合。这是取得原文本的证据，不能视为法律兼容、发行或用户验收通过。

复现仅离线文件操作：

```bash
python3 docs/java-migration/delivery/license_bundle.py --repo "$PWD" \
  --maven-repository /Users/fsy/.m2/repository --npm-cache /Users/fsy/.npm/_cacache
```

工具重新生成本地artifact部分索引；官方远程下载尚未实现，工具保留已有public_versioned_sources远程元数据，不能把离线重跑当官方文本已取得。本窗口没有Docker、构建、PG、HTTP应用或浏览器进程。所有已有image/save/恢复资产不变。

## 官方版本文本恢复（2026-10-28）

先前raw下载TLS错误记录保留。主Agent改用官方GitHub contents API成功取得Node v22.23.4聚合LICENSE（含内置组件原有条目）与pgvector v0.8.4 LICENSE原字节，校验Git blob SHA1并记录源commit、原始字节SHA256与长度，索引public_versioned_sources。未重建或转写许可正文；本次归档不替代实际镜像文件来源核验或法律兼容签认。其余Maven/npm无内置文本、缓存缺失和容器原文本仍按逐项索引保留，不把两份补齐扩大为完整再分发NOTICE已通过。
