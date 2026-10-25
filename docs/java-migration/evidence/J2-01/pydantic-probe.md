# J2-01 冻结版本 422/JSON 行为探针记录

来源：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv`
（J0-02 oracle 同一虚拟环境，只读使用；fastapi 0.115.6 / pydantic 2.10.4，与
backend/requirements.txt 钉定版本一致）。探针为最小 FastAPI 应用 + TestClient，
仅复现 LoginRequest 模型行为，未执行仓库内任何脚本。

## 请求体校验（POST 模型 {username: 1..64, password: 1..256}）

| 输入 | 状态 | 固化响应 |
|---|---|---|
| `""`（空体） | 422 | `{"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}` |
| `"{"` | 422 | `{"type":"json_invalid","loc":["body",1],"msg":"JSON decode error","input":{},"ctx":{"error":"Expecting property name enclosed in double quotes"}}` |
| `'{"a":"b"} extra'` 形尾随 | 422 | `json_invalid`，loc=尾随首字符位（`{"username":"a","password":"b"} extra` → 32），ctx.error=`Extra data` |
| `"[1,2]"` / `'"x"'` | 422 | `{"type":"model_attributes_type","loc":["body"],"msg":"Input should be a valid dictionary or object to extract fields from","input":<原值>}` |
| `"{}"` | 422 | 两条 missing，按字段序，input=整个 body 对象 |
| `'{"username":null}'` | 422 | username `string_type`(input=null) + password `missing`(input={"username":null}) |
| `'{"username":""...}'` | 422 | `string_too_short`，msg `String should have at least 1 character`（单数），ctx `{"min_length":1}` |
| 65 字符 username | 422 | `string_too_long`，msg `String should have at most 64 characters`，ctx `{"max_length":64}` |
| `'{"username":123,...}'` | 422 | `string_type`，msg `Input should be a valid string`，input=123 |
| 多余字段 | 200 | 忽略 |
| 键序 | — | 错误条目键序 type/loc/msg/input/ctx；无 url 键（与 golden 一致） |

## Python json.JSONDecodeError msg/pos 语义（json_invalid 的 ctx.error 与 loc）

对 `json.loads(s)` 实测：`Extra data` pos=值后首个非空白字符；`Expecting value` pos=违规
字符（`'x'`→0、`'  '`→2、`'{"a":}'`→5）；`'{'`→`Expecting property name enclosed in
double quotes`@1；`'{"a"1}'`→`Expecting ':' delimiter`@4；`'{"a":1,'}`（尾逗号）→
`Expecting property name...`@7；`'{"a"1}'`→Expecting ':'@4；串内控制字符→
`Invalid control character at`@字符位；坏 `\u`→`Invalid \uXXXX escape`@'u' 位；
未闭合串→`Unterminated string starting at`@开引号位；`'01'/'1.'/'1e'`→顶层
`Extra data`@数字合法前缀后；`'-x'`→Expecting value@0；`NaN`/`-Infinity` 合法。

## Java 侧实现落点

`io.dpdk.assistant.auth.PythonJsonScanner`（按上述语义精确复刻 msg/pos）+
`PydanticBody`（missing/json_invalid/model_attributes_type/字段四类错误）。
AuthContractTest.pydanticBodyFidelityBeyondGolden 逐条断言上表（空体/`{`/尾随/
数组/null/空串）。

## 依赖求值顺序（影响状态码优先级）

同一 venv 内 fastapi/routing.py:267-280（只读核对）：JSONDecodeError →
RequestValidationError([{"type":"json_invalid","loc":("body", e.pos), ...}])。
依赖（require_auth）求值先于体校验：golden change-password::unauthorized 空/不
完整体得 401 而非 422（矩阵 §0 已按此修正）。
