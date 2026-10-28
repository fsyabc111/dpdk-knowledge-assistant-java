# J5 bcrypt 同源 JNI 可行性草稿与停止恢复点

2026-10-28，owner=model_adapter。**用户已要求收束：立即停止Rust/JNI及其他新技术支线。当前仅交接，不继续开发、不编译、不跑新验证，不集成原型生产。**

## 当前完成语义

- 已做：只读核对官方pyca/bcrypt 4.3.0、Rust bcrypt及jni发布源码/锁；获父Agent隔离proof授权后写入gitignored草稿、取得原pyca锁与来源元数据。
- **未做：没有执行Rust/Cargo、rustfmt、javac、JNI加载、密码互验、微基准或新的HTTP/PG负载。** 源码仅草稿，Rust/JNI API是否能编译尚未证实，所有兼容与性能结论待验证。
- 没改生产、pom、正式协议、黄金、阈值或密码策略；JNI未进入生产classpath。没有为性能降低cost12、缓存成功密码或替代dummy工作。
- 父Agent负责隔离Rust工具链准备；本worker没有安装工具链、没有工具链下载/编译子进程，也不停止/删除父Agent的资产。

## 唯一草稿目录与现存文件

绝对目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/bcrypt-jni-proof-20261028`。该路径已由git check-ignore确认被忽略；所有草稿/来源锁均保留。

| 文件 | SHA256（停止时现存字节） |
|---|---|
| Cargo.toml | 3d5699f634908c59568ec177f3dbe7240bf7be4ddbe5ff53fdbf3fcdfd6f0f2f |
| src/lib.rs | a055380e67cde8f34205a9744f7b9d000e65d28e38b311397492f035f034a6dc |
| NativeProbe.java | 7edf38ca05a9ad32817b1f394bf967c0f872a7f03c5249f99c744cff8bc7d844 |
| pyca-4.3.0.Cargo.lock | 7c0a07076f320154e5dea07ee602fc9b1b35851ef96bfcfac8deb463f2ada147 |
| python_reference.py | 55963ffb41b72fff3dd0d1461942193a65393ad23e6de584fdc55e8adce148ec |
| SOURCE_AUDIT.json | 7bc9d58ddb680ebdfcd40021c0692092859d61a3c3c0b7af49ec96e8f40a9af5 |
| README.md | 0ff64dac0d4b6c91d59943cbc6758f4d085523c86089cf4ab2e0919a66364f3f |

**proof自己的Cargo.lock尚不存在。** `pyca-4.3.0.Cargo.lock`只是官方源项目锁，不能冒称proof已锁定或已经可用`cargo build --locked`。停止时目录没有target、classes、native artifact或兼容/性能结果。父Agent工具链是否已下载、实际绝对路径、隔离CARGO_HOME/RUSTUP_HOME与版本，须向父Agent当前恢复点核对，本worker不猜测默认环境。

## 官方来源与精确依赖事实

| 项 | 固定版本/来源 |
|---|---|
| pyca/bcrypt | 4.3.0，tag解引用commit `16ba2ab22bfc31f23f34a7b678c9eee8c9d3dbe7`，Apache-2.0；[Cargo.toml](https://github.com/pyca/bcrypt/blob/4.3.0/src/_bcrypt/Cargo.toml)、[Cargo.lock](https://github.com/pyca/bcrypt/blob/4.3.0/src/_bcrypt/Cargo.lock)、[适配源码](https://github.com/pyca/bcrypt/blob/4.3.0/src/_bcrypt/src/lib.rs) |
| Rust bcrypt | 0.17.0，MIT；registry checksum `92758ad6077e4c76a6cadbce5005f666df70d4f13b19976b1a8062eef880040f`；[tag源码](https://github.com/Keats/rust-bcrypt/tree/v0.17.0) commit `a03a9449fef6bd57303366f42a2f95e4230a286f` |
| jni | 0.22.4，MIT OR Apache-2.0；[稳定发布](https://github.com/jni-rs/jni-rs/releases/tag/v0.22.4) commit `5ae9458a4ec44c5318f37ddc7569c1d4ae8a69e7`；registry checksum `5efd9a482cf3a427f00d6b35f14332adc7902ce91efb778580e180ff90fa3498`；[Cargo明确MSRV1.85](https://github.com/jni-rs/jni-rs/blob/v0.22.4/Cargo.toml) |

来源核对通过官方GitHub tag/contents API与官方rust-lang/crates.io-index完成；没有下载执行第三方程序。SOURCE_AUDIT记录原核心路径：bcrypt0.17.0、blowfish0.9.1、cipher0.4.4、base64 0.22.1、subtle2.6.1、zeroize1.8.1、getrandom0.3.1、byteorder1.5.0以及计算核心间接依赖。Cargo草稿固定这些计算核心版本；新JNI依赖的完整lock解析/校验尚未完成。

准确边界：Rust bcrypt的commit是官方v0.17.0 tag commit；未下载crate归档检查`.cargo_vcs_info.json`。registry checksum来自pyca原锁；若后续proof获取归档，还必须验证下载字节与此checksum，不把tag与归档同一性检查伪报为已完成。

## 已查证语义与草稿设计

pyca4.3.0的hashpw先对原始bytes截前72，再解析2a/b/y（源码还接受2x）、编码cost、前22字符salt解成16 bytes，调用`bcrypt::hash_with_salt`，按原prefix格式化，checkpw使用subtle对完整encoded hash作常数时间比较。默认生成cost12；NUL自4.0允许，4.3路径不经过C字符串。Rust core也有NUL区分测试。

因此同crate薄JNI有合理可行性，不需要Python/PyO3/maturin运行时，也不自写密码算法；但**同源码不是性能证明**，LLVM/Rust版本、release profile、目标CPU及JNI/线程排队均会影响结果。没有现成密码库生产接受结论。

草稿只复用上述适配链，不调用`bcrypt::verify`捷径：后者比较解码尾hash，与pyca对整个encoded字符串比较的解析/非canonical边界不能直接视为等价。Java传标准UTF8 byte[]，native复制region后再hash，不持有JNI critical array/GC pin。

JNI草稿返回1=MATCH、0=MISMATCH，-1输入/crypto错误、-2JNI错误、-3panic、-4内部错误；错误绝不accept。采用jni EnvUnowned/with_env边界与code-only policy。**其静默panic hook为独立proof JVM的全局策略，不可直接照搬生产；没有编译/故障执行证明。**

NativeProbe草稿覆盖正常/错误密码、2a/b/y、UTF8/补充字符、NUL、72/73 bytes、UTF8恰在第72 byte切断、空串、非canonical salt/tail、非法salt/hash/cost及legacy2分类。报告保留具体错误类别和差异，不改expected，也没有Spring fallback掩盖差异。

性能驱动草稿使用冻结J2-01同一cost12 hash、同一合成密码，serial60或60×25ms错峰；后者计入提交后排队，并另报计划到达/提交滞后。输出明确标记throwaway microbenchmark，不是HTTP gate。Python helper仅用于现有4.3.0离线参考，不启动Python后端。

## 平台/工具链及实质风险

- 如以后获新授权，采用jni0.22.4至少需要Rust/Cargo1.85、本机linker/SDK及JDK21；无需Python构建/运行依赖。bcrypt0.17 tag CI曾测试Rust1.63，但这不是新JNI组合的最低构建版本承诺。
- Mac ARM目标`aarch64-apple-darwin`生成Mach-O dylib，需要Apple linker/SDK；Linux AMD64目标`x86_64-unknown-linux-gnu`生成ELF so，需要Linux linker/glibc基线。两者必须分别构建核验，不能互载，Mac证明不外推Linux，也不默认启用target-cpu=native。
- 新JNI glue、panic/OOM/异常转换、加载路径/发布/回退与许可证链都需独立审核；Rust core的成熟性不自动证明自己写的FFI安全。
- 尚未测出的兼容差异（尤其非canonical salt、畸形hash错误、72-byte边界）不得标通过或改expected；受限native范围+Spring fallback只能作为以后另行设计，当前proof没有这种隐藏分支。
- 当前没有性能收益、CPU/RSS或HTTP门禁结论；不构成修改PasswordHasher.check或pom授权。

## 停止后的准确恢复入口（以下命令均未执行）

**只有新的直接用户授权及root分配CPU窗口后才允许继续。当前不得因为本交接有命令就自动恢复支线。** 先确认工具链/路径、当前源码SHA及所有既有正式资源，不占用root fullverify/nativeCLI/容量窗口。

```sh
JNI_PROOF_DIR=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/bcrypt-jni-proof-20261028
# 必须由root当前恢复点填写真实隔离工具链路径；下列CONFIRMED不是现存入口。
JNI_PROOF_CARGO=/absolute/CONFIRMED/cargo
JNI_PROOF_RUSTC=/absolute/CONFIRMED/rustc
JNI_PROOF_PYTHON43=/absolute/CONFIRMED/frozen-bcrypt430/python
JNI_PROOF_JAVA=/opt/homebrew/opt/openjdk@21/bin/java
JNI_PROOF_JAVAC=/opt/homebrew/opt/openjdk@21/bin/javac
JNI_PROOF_SPRING=/Users/fsy/.m2/repository/org/springframework/security/spring-security-crypto/7.0.8/spring-security-crypto-7.0.8.jar

# 获新授权后先核对现存源，不覆盖草稿或删除下载。
shasum -a 256 "$JNI_PROOF_DIR/Cargo.toml" "$JNI_PROOF_DIR/src/lib.rs" "$JNI_PROOF_DIR/NativeProbe.java" "$JNI_PROOF_DIR/pyca-4.3.0.Cargo.lock"
# 必须先生成proof自己的锁，再逐包核对SOURCE_AUDIT中的版本/checksum；当前锁不存在。
RUSTC="$JNI_PROOF_RUSTC" "$JNI_PROOF_CARGO" generate-lockfile --manifest-path "$JNI_PROOF_DIR/Cargo.toml"
# 锁有漂移则停止，不带着不匹配的lock继续；保留panic=unwind、thin LTO、overflow checks。
RUSTC="$JNI_PROOF_RUSTC" "$JNI_PROOF_CARGO" build --release --locked --target aarch64-apple-darwin --manifest-path "$JNI_PROOF_DIR/Cargo.toml"
mkdir -p "$JNI_PROOF_DIR/classes"
"$JNI_PROOF_JAVAC" -cp "$JNI_PROOF_SPRING" -d "$JNI_PROOF_DIR/classes" "$JNI_PROOF_DIR/NativeProbe.java"
JNI_PROOF_LIB="$JNI_PROOF_DIR/target/aarch64-apple-darwin/release/libbcrypt_jni_proof.dylib"
JNI_PROOF_CP="$JNI_PROOF_DIR/classes:$JNI_PROOF_SPRING"
"$JNI_PROOF_JAVA" -cp "$JNI_PROOF_CP" NativeProbe fixtures "$JNI_PROOF_DIR/fixtures.json"
"$JNI_PROOF_PYTHON43" "$JNI_PROOF_DIR/python_reference.py" "$JNI_PROOF_DIR/fixtures.json" "$JNI_PROOF_DIR/python-reference.json"
"$JNI_PROOF_JAVA" -cp "$JNI_PROOF_CP" NativeProbe compat "$JNI_PROOF_LIB" "$JNI_PROOF_DIR/spring-native-compat.json"
# 先逐条比对Python/Spring/native分类与具体错误，差异不得被summary覆盖；再决定是否值得计时。
```

后续若有独立性能授权，各provider/模式用独立新JVM，保持原flags与同warmup计数；下列是驱动参数入口，不是已完成的结果：

```sh
"$JNI_PROOF_JAVA" -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -cp "$JNI_PROOF_CP" NativeProbe bench "$JNI_PROOF_LIB" spring serial 20 "$JNI_PROOF_DIR/spring-serial.json"
"$JNI_PROOF_JAVA" -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -cp "$JNI_PROOF_CP" NativeProbe bench "$JNI_PROOF_LIB" native serial 20 "$JNI_PROOF_DIR/native-serial.json"
"$JNI_PROOF_JAVA" -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -cp "$JNI_PROOF_CP" NativeProbe bench "$JNI_PROOF_LIB" spring stagger 20 "$JNI_PROOF_DIR/spring-stagger.json"
"$JNI_PROOF_JAVA" -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -cp "$JNI_PROOF_CP" NativeProbe bench "$JNI_PROOF_LIB" native stagger 20 "$JNI_PROOF_DIR/native-stagger.json"
```

Linux AMD64必须在另行确认的Linux构建环境把target/lib后缀换为`x86_64-unknown-linux-gnu`/`.so`并重新验证ABI、source/dependency/artifact SHA与完整兼容；此处没有授权或提供跨编译器/镜像安装操作。

## 资源与交回

本worker只有已结束的只读source/API与文件写入动作，没有Rust/Cargo/javac/JVM/PG/服务/Docker后台进程；没有占用CPU/PG窗口。当前只提交本交接文档，ignored原型及下载/锁全部保留，不进生产Git。正式迁移的状态、cache复核、fullverify/CLI/matrix/指标结论由root记录，本支线不代替它们。后续是否重启或彻底放弃native路线，由用户另行决定。

## 主Agent最终核对（2026-10-28收束）

工具链实际隔离根为主checkout `.migration-artifacts/rust-isolated-20261028`；CARGO_HOME=其cargo、RUSTUP_HOME=其rustup，PATH若未来新授权才加cargo/bin。当前rustc缺失、工具链未ready；不能运行这里恢复命令或Cargo构建。所有下载/安装已受控退出，无全局shellRC/PATH修改，partial及源码完整保留；详evidence/J5-04/rust-isolated-preparation-stopped.json和MAIN。proof未编译、proof Cargo.lock未生成、未互验未性能测量未集成生产，主Agent已只读检查交付jar不含NativeProbe/NativeBcrypt或proof库。用户明确停止本支线，后续须新指令而非默认续跑。
