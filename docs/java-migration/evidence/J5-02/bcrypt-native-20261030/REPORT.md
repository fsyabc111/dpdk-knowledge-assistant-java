# J5 登录性能：pyca同源native bcrypt（2026-10-30，诊断，非正式容量）

用户2026-10-30直接授权继续JNI/Rust方案。结论：**Java登录的bcrypt成本已与Python基线持平，Java特有的并发劣势消除**；冻结门槛312.443ms下单次诊断仍受机器环境波动影响，Python基线在同窗口也会超限。正式72分钟双端容量未跑，`formal_capacity_pass=null`。

## 实现

- Rust crate `backend-java/native/bcrypt-jni`：按pyca/bcrypt 4.3.0 checkpw适配序列（口令原始字节截72、解析2a/2b/2y/2x前缀/cost/盐、`bcrypt::hash_with_salt`、完整编码串subtle常数时间比较）调用bcrypt 0.17.0；不自写密码算法、不走`bcrypt::verify`捷径。JNI只复制byte[] region，不持有critical数组。
- 返回码1/0/-1输入错误/-2 JNI/-3 panic/-4内部；Java侧-1→IllegalArgumentException（对应Python ValueError），其余负值→IllegalStateException，绝不当作通过。
- `NativeBcrypt`：`BCRYPT_PROVIDER=auto|native|spring`、`BCRYPT_NATIVE_LIBRARY`；加载后ABI与Python dummy-seed向量自检，失败拒绝启动。`PasswordHasher.check`可用时走native，hash/dummy生成、cost12不变。
- 工具链：隔离Rust 1.99.1（官方组件SHA逐个校验，仅子进程env，不改全局）。生产Cargo.lock与pyca 4.3.0原锁核心13包版本/checksum逐一相等，仅新增jni依赖链。

## 兼容（35类向量）

| 对比 | 分类差异 |
|---|---:|
| Python 4.3.0 vs native | **0** |
| Python 4.3.0 vs Spring 7.0.8 | 2（非规范salt末字符、旧`$2$`前缀：Python抛错，Spring返回false） |

覆盖2a/2b/2y、错误口令、UTF-8/补充字符、NUL及NUL后差异、72/73字节与UTF-8在第72字节切断、空口令、cost12冻结向量、非法前缀/cost/盐字母表、截断/延长/非规范尾部、缺段。JNI null输入得-2、人为panic得-3。差异已登记`interface-changes.md` J5-BCRYPT-NATIVE；spring模式保留旧差异。生产库另由NativeBcryptTest以同类向量复验。

## 性能

微基准（同生产JVM参数、独立JVM、warmup20、throwaway，不是HTTP门禁）：

| | 串行p95 | 60×25ms错峰p95 |
|---|---:|---:|
| Spring BCrypt | 232.4ms | 552.1ms |
| native | 172.7ms | 236.1ms |

编译变体（fat LTO+codegen-units=1、再加target-cpu=native）串行均约174ms，错峰差异在噪声内，不采用非默认参数。

真实HTTP登录诊断（`acceptance/load/diagnose_login.py`，真实应用/owned PG/真实身份与token事务，protocol_sha256 `e50af779…`未变；门槛312.443ms）：

| 轮次 | serial60 p95 | stagger无采样p95 | stagger带采样p95 |
|---|---:|---:|---:|
| 旧Java Spring（2026-10-28） | 233.1 | 997.6 | 850.7 |
| Java native java01 | 183.4 | 264.8 | 325.1 |
| Java native java02 | 192.1 | 266.9 | 345.7 |
| Python 4.3.0 python01（同窗口） | 180.6 | 266.9 | 321.1 |
| Java native java03 | 181.5 | 278.5 | 315.3 |
| Java native java04 | 192.6 | 231.2 | **245.9（三项全过）** |

四轮全部0错误。Java服务端日志均为`bcrypt provider: native (classpath:/native/darwin-aarch64/libdpdk_bcrypt_jni.dylib)`；java04起诊断summary按日志记录provider与库SHA（`74e0c969…`），java01–03的summary中“Spring BCrypt”字样是旧脚本写死标签，以server.log为准。

## 回归

- 完整`mvnw verify`（专属PG55481，测试JVM实际native）：794执行、0失败、0错误、3跳过（既有DefaultBoundaryPgTest三项）。
- `BCRYPT_PROVIDER=spring`：NativeBcryptTest 5项中3项按设计跳过，CredentialLifecycleTest 14/14。

## 未完成

- 正式同协议3轮双端（72分钟）未跑；单次诊断不代替正式容量判定。
- CI未构建native库（CI中native用例跳过、走spring）；Docker镜像未加Linux .so构建阶段与`BCRYPT_PROVIDER=native`；Linux平台未构建/互验；Rust依赖许可清单未并入J5-04。
- 原始日志/summary/proof在`.migration-artifacts/`（gitignored）：`login-diagnosis-native-20261030-*`、`bcrypt-jni-proof-20261028`、`bcrypt-jni-variants-20261030`、`native-bcrypt-20261030-full-verify.log`。
