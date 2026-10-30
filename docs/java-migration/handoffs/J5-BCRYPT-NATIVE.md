# J5 native bcrypt 集成交接（2026-10-30）

用户2026-10-30直接授权“继续JNI/Rust方案解决登录性能”。主Agent单独执行，无worker。取代J5-BCRYPT-JNI-PROOF.md的“停止”状态；proof目录保留不删。

## 变更路径

- `c5627fb`：`backend-java/native/bcrypt-jni/`（Cargo.toml/Cargo.lock/src/lib.rs/build.sh）、`auth/NativeBcrypt.java`、`auth/PasswordHasher.java`、`NativeBcryptTest.java`、`pom.xml`（process-resources复制`native/bcrypt-jni/dist`）、`migration/contracts/interface-changes.md`（J5-BCRYPT-NATIVE）。
- `441e041`：`acceptance/load/diagnose_login.py`按server.log记录实际provider与库SHA（不在protocol_sha256文件集内）。
- 证据：`evidence/J5-02/bcrypt-native-20261030/{REPORT.md,summary.json}`。

## 接口/决策差异

新增Java专用环境变量`BCRYPT_PROVIDER`、`BCRYPT_NATIVE_LIBRARY`；畸形哈希（非规范salt、`$2$`）由false改为IllegalArgumentException，向Python4.3.0对齐。hash生成、cost12、dummy等价工作、阈值、负载未改。

## 实际验证

- 隔离Rust1.99.1：续传官方组件SHA全部校验后安装；rustup按旧记录多装了rust-docs/rustfmt/clippy（官方源、rustup校验）。
- 兼容35类向量：Python/native 0差异，Python/Spring 2差异；JNI null→-2、panic→-3。
- 完整verify：794/0失败/0错误/3既有DefaultBoundary跳过，测试JVM native启用；spring模式NativeBcryptTest 3跳过、CredentialLifecycleTest 14/14。
- 真实HTTP登录诊断4轮Java+1轮Python，见REPORT；java04三探针全低于312.443ms，其余轮带采样探针与Python同样超限。

## 未验证/阻塞

- 正式72分钟双端3轮未跑（需用户另行授权CPU窗口）。
- CI未装Rust：native用例在CI跳过、走spring。Docker `deploy-java/Dockerfile.backend`未加Rust构建阶段；容器/tmp为noexec，须把.so放`/app/native/`并设`BCRYPT_PROVIDER=native`、`BCRYPT_NATIVE_LIBRARY`。当前网络约32KB/s/连接，拉取Rust基础镜像预计很慢。
- Linux aarch64/x86_64未构建互验；Rust依赖许可清单未进J5-04；新源jar/CLI/CI未重跑。

## 回退

`BCRYPT_PROVIDER=spring`即回到旧实现（不需重新构建）；代码回退`git revert 441e041 c5627fb`。

## 资源与恢复

- 专属PG55481本轮启动（p1_java、data_directory已核），收尾时确认0客户端后smart停止；诊断库按owner生命周期保留/清理同既有规则；恢复命令见MAIN.md第7节。
- 隔离Rust：`.migration-artifacts/rust-isolated-20261028`（CARGO_HOME=cargo、RUSTUP_HOME=rustup、PATH前缀cargo/bin、RUSTUP_TOOLCHAIN=1.99.1-aarch64-apple-darwin，仅子进程）。构建：`backend-java/native/bcrypt-jni/build.sh`。
- 原始资产（gitignored）：`.migration-artifacts/login-diagnosis-native-20261030-{java01..04,python01}`、`bcrypt-jni-proof-20261028`、`bcrypt-jni-variants-20261030`、`native-bcrypt-20261030-full-verify.log`、`native-bcrypt-20261030-classpath.txt`。
