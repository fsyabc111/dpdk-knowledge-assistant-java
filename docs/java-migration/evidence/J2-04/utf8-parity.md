# J2-04 UTF-8 替换编解码一致性（验收 F）

## 背景

提交链路两处 Python 编解码语义必须逐字节复现：
1. multipart 上传 `data.decode("utf-8", errors="replace")`，随后成绩 hash 对
   替换后文本再 `encode("utf-8", errors="replace")`；
2. JSON 粘贴 `code.encode("utf-8", errors="replace")`（字符串可能含孤立代理）。

## 关键发现：JVM 与 CPython 的分歧点

对本机 CPython（fixture 录制同族行为）与 JVM `new String(bytes, UTF_8)` 做差分：
两者在**全部探测分支一致，唯独「首续字节合法、整段落进 UTF-16 代理区」的三字节
序列（ED A0 BF）**——JVM 把整段 3 字节记一次 malformed（1 个 U+FFFD），CPython
按 ED 首续字节限 80..9F 逐字节拒绝（3 个 U+FFFD）。

因此实现 `PythonUtf8.replaceDecode(byte[])`（CPython utf8_decode 字节机）：
- 起始字节 0x80..0xC1 立即非法（含 C0/C1 过长编码）；
- E0 首续 A0..BF、ED 首续 80..9F、F0 首续 90..BF、F4 首续 80..8F；
- 续字节非法 → 已累积前缀 1 个 U+FFFD，失败字节从起始态重处理；
- 输入在多字节序列中途结束 → 整段前缀 1 个 U+FFFD。

编码侧无需特判：非代理文本 `String.getBytes(UTF_8)` 与 Python 逐字节一致；
孤立代理两侧都替换 `'?'`（0x3F）。

## 冻结电池（ AssignmentSubmitContractTest.pythonUtf8ReplaceHashParity）

sha256( replaceDecode(raw) 再 encode-replace )，Python 值由本机 CPython
`raw.decode('utf-8','replace').encode('utf-8','replace')` 计算后冻结：

| 用例 | raw（hex/文本） | Python sha256 前 16 位 |
|---|---|---|
| valid_ascii | "int main() { return 0; }" | 80a7161009ffaf86 |
| surrogate_cesu | eda080 | a73f4cb996ceb6ee（JVM 会错出 83d544…） |
| overlong_c0af | c0af | 52793f8dc1d85e40 |
| truncated_tail | 6162 e282（尾部截断） | 7ad10e79f47f28b4 |
| stray_cont | 8081 | 52793f8dc1d85e40 |
| mixed | 61 ff 62 f090 28 bc f4908080 | c4ac6638a0253b2b |
| valid_utf8_cn | 中文路径 | 1bab176604b9a218 |
| f0_80_80_80 | f0808080 | c22917d19a665676 |
| c2_bare | c2 | 83d544ccc223c057 |
| e0_80_mid | e080 61 | 85c19ece421fc0dd |
| ed_a0_tail | eda0（尾部） | 52793f8dc1d85e40（区分「首续非法」与「尾部截断」） |
| f4_90_80_80 | f4908080 | c22917d19a665676 |
| e2_82_61 | e282 61 | 94b964456d33b6a0 |
| JSON 孤立代理 | "a\ud800b"（字符串字面量） | c2a7b64a2d252004…（'?' 替换） |

全量 64 位值在测试源码内（`AssignmentSubmitContractTest.pythonFrozen`），
测试断言 Java 实现输出与冻结值逐字节相等。
