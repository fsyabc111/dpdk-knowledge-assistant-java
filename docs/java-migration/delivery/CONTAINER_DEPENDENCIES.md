# 容器实际依赖与许可证声明库存（P4/锁定base）

本库存依据新colima-java-u12内已有不可变image实物，非标签推断。完整机器清单在`../evidence/J5-04/container-inventory-p4.json`，输入/image ID/版本/每包license出处/文本路径及SHA256均可复核；摘要在`container-inventory-summary.json`。它补齐此前容器库存准备项；最终P5 candidate镜像、完整再分发NOTICE文本包、许可证法律兼容结论仍未完成。

| 镜像 | 使用域 | 实际OS | installed OS包数 | 无结构化License字段 | license文本文件hash数 |
|---|---|---|---:|---:|---:|
| p4-backend | P4回退后端运行层 | Ubuntu 26.04.1 LTS | 140 | 15 | 322 |
| p4-frontend | P4回退前端运行层 | Alpine Linux v3.21 | 68 | 0 | 5 |
| jdk-base | 后端builder | Ubuntu 26.04.1 LTS | 149 | 23 | 395 |
| jre-base | 后端运行base | Ubuntu 26.04.1 LTS | 140 | 15 | 322 |
| node-base | 前端builder；隔离fake最终实际image另核 | Alpine Linux v3.24 | 19 | 0 | 196 |
| nginx-base | 前端运行base | Alpine Linux v3.21 | 68 | 0 | 5 |
| pgvector-base | 隔离PG base | Debian GNU/Linux 12 (bookworm) | 144 | 20 | 145 |

计数按镜像分别统计，7份共728条installed OS包记录、1390个文件hash记录，包含继承重复，不是去重组件数量。PG dpkg数据库另有8条已卸载残留状态，已排除installed依赖并保留于noninstalled字段。dpkg来源为实际dpkg-query状态/版本/source与copyright，Alpine来源为/lib/apk/db/installed的P/V/A/o/L字段。声明原样保留，不强制改为SPDX，不从包名猜许可证。

## 实际运行时/构建工具

| 组件 | 实物版本依据 | 许可证声明/文本证据与边界 |
|---|---|---|
| Temurin JDK/JRE | `java -version`及/opt/java/openjdk/release：21.0.12.1+1-LTS，IMPLEMENTOR Eclipse Adoptium，分别IMAGE_TYPE JDK/JRE | legal/java.base/LICENSE、ADDITIONAL_LICENSE_INFO、ASSEMBLY_EXCEPTION包含GPLv2/Classpath声明；模块legal目录全部文件及NOTICE逐文件hash。不能用Java主许可证代替各模块第三方条款；逐组件授权路径尚未法律核准。 |
| Node | 实际`node --version`22.23.4；process.versions记录真实V8/OpenSSL/libuv/ICU等版本以及ABI等构建信息 | Node手工安装的binary不出现在apk19包中；process.versions不是许可证声明。已扫描/usr/local、/opt与常用license目录，未取得Node主程序及其全部编译内置依赖的独立主LICENSE文本；该部分文本溯源待补，不猜MIT覆盖全部组件。 |
| npm | 实际`npm --version`10.9.9；安装树package.json | npm根package.json声明Artistic-2.0。Node安装树共198份有效name/version包元数据（含npm、Yarn及嵌套依赖），逐份记录license/旧licenses声明、package.json hash及根license文件hash；它不同于应用frontend lock的213条。 |
| Yarn | /opt/yarn-v1.22.22/package.json：1.22.22 | package.json声明BSD-2-Clause；实际LICENSE文件hash已取。编译bundle内依赖未因此变成逐组件完整SBOM。 |
| nginx | `nginx -v`1.27.5；apk nginx1.27.5-r1；njs模块1.27.5.0.8.10-r1 | 实际apk声明为`2-clause BSD-like license`，不擅改原文；nginx及4个模块COPYRIGHT文本hash齐。其他Alpine包若只有L字段而缺专属文本路径，逐包标metadata-only，不能当许可证全文已交付。 |
| PostgreSQL | 实际postgres --version与pg_config：17.10（Debian17.10-1.pgdg12+1） | 实际postgresql-17/copyright有PostgreSQL、BSD、Tcl及其他子部分声明，原始声明列表与hash保留；不把全镜像统一归为PostgreSQL许可。 |
| pgvector | /usr/share/postgresql/17/extension/vector.control：default_version0.8.4；vector.so/control文件hash | 不在dpkg installed包中；在已扫描目录未找到专属pgvector LICENSE文本。不能据镜像标签/项目常识代填许可，明确待取得匹配版本的文本。 |

Debian/Ubuntu部分copyright是非结构化全文，没有`License:`字段：p4-backend15、jdk-base23、jre-base15、PG20；这些installed包的copyright文本均存在/hash已取，声明识别仍标unverified，不等于没有许可证。Alpine逐包LICENSE文本缺失与已有元数据声明分开标。Node旧格式qrcode-terminal的licenses数组已据实际package.json提取Apache2.0声明，无需从README猜测。完整未知列表可按metadata_missing_license/declaration_status/license_text_status字段查询机器清单。

## 继承、复现与待补

P4后端与锁定JRE base在installed OS包记录、license文件映射、os-release及Java release四域逐项相同；P4前端与锁定nginx base也在四域相同。该继承证据不包括应用jar或前端bundle；它们仍使用P4 image归档及Maven/npm交付清单。JDK/Node是构建域，不能因为库存存在就称已进最终frontend runtime。Compose fake使用的最终实际image ID与其挂载脚本版本须在候选冻结时另绑定，不沿用可变tag作版本证明。

采集只用显式新context；每镜像前核对endpoint+daemonID+Name，镜像ID须匹配固定allowlist。所有短容器--pull never、--network none、--read-only、nobody、cap-drop ALL、no-new-privileges、限制CPU/memory/pids且覆写entrypoint=/bin/sh；无端口/挂载/应用启动/PG服务/stack。容器--rm自动清理，不删除image/save/恢复资产。没有联网包管理器或真实模型调用。

```bash
# 仅在主Agent允许环境准备的窗口执行；正式容量CPU独占时不执行。
export DPDK_JAVA_DOCKER_CONTEXT=colima-java-u12
export DPDK_JAVA_DOCKER_ENDPOINT=unix:///Users/fsy/.colima/java-u12/docker.sock
export DPDK_JAVA_DOCKER_DAEMON_ID=8f66e5ea-9c07-4379-b27d-7a5759cc2cd3
python3 docs/java-migration/delivery/container_inventory.py \
  --output "$PWD/.migration-artifacts/j5-03/container-inventory-fresh.json"
```

输出必须新路径，禁止覆盖已有证据。最终候选尚需核对实际image/base ID、OS包/许可证指纹、Node/PG等实际版本与对应源码/配置；若发生变化重新库存。缺失Node主LICENSE/内置依赖、pgvector文本、完整NOTICE再分发包及候选差异闭合前，不能代签完整法律/用户/live/生产验收。
