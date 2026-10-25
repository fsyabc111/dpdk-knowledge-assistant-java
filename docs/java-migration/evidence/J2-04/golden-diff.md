# J2-04 golden 差分（python-golden.json @p0-v2）

比对对象：`migration/fixtures/p0-v2/python-golden.json` 的三条 submit observation
（复制为测试资源 `backend-java/src/test/resources/migration/J2-04/golden-submit.json`）。

## 归一化映射（其余字段逐字节精确比对，零归一化）

| 动态字段 | 归一化规则 | 依据 |
|---|---|---|
| `submission_id`（body/DB 行） | 正则 `^grade_[a-f0-9]{16}$` | Python `f"grade_{uuid4().hex[:16]}"` |
| `assignment_id`（路径参数） | 测试内以真实创建 id 替换 golden `<identity:19>` | oracle identity 指针 |
| `accepted_at` | 形态正则 `^…T…(.\d{6})?\+00:00$`（Python isoformat） | 值为受控时钟打点，逐字节值随时钟 |
| `assignment_updated_at`（accepted_assignment 内） | 形态正则（Python str(datetime)，空格分隔） | 与 J2-03 catalog 渲染同语义 |
| `usage_counters.day` | 测试墙钟日 `2026-10-25`（golden 为 oracle 打点日 `2026-10-24`） | 日键=时钟日期，非行为差异 |
| secure-headers（CSP 等 4 头） | 不比对 | 平台层差异，J2-01 已登记未实现 |

## 逐条结果（AssignmentSubmitContractTest.goldenFlowSubmitSuccess）

1. `POST /api/assignments/{assignment_id}/submit::success`（expected 200）
   - body：score/passed/total/points/earned/best_earned 与 golden 逐值相等；
     `results` 与 golden 逐字段全等（synthetic-case FAIL + failures 三键）；
     submission_id 归一化后相等。
   - side_effect_counts：mcp=1（harness.gradeCallCount delta=1）。
   - business_delta：grading_submissions +1 行逐字段（user_id/protocol=dns/
     code_hash=`1d6051bd…`（golden 请求的 code_sha256，按 runtime source
     sha256 程序化复现）/assignment_id/session_id NULL/task_job_id NULL/score/
     passed/total/results_json.source_origin=pasted/grade_details.results 全等/
     accepted_assignment 六字段）；usage_counters +1（kind=grading）。
2. `POST …::unauthorized`（expected 401）：`{"detail":"missing or malformed bearer
   token"}` 逐字节。
3. `POST …::invalid-input`（expected 422）：`{"detail":[{"type":"missing",
   "loc":["protocol"],"msg":"Field required","input":{}},{"type":"missing",
   "loc":["code"],…}]}` 逐字节——**手工构造模型的 loc 无 "body" 前缀**，与
   FastAPI 函数参数校验（J2-01 auth 的 ["body",field]）不同族，注意区分。

## runtime source 复现

golden 请求 `code` 在 fixture 中掩码为 `<runtime-source>`，其 sha256
`1d6051bdbbdc6dd9cffb8848b37f5c81265eccdad2b9e3cdbe25e09a67464c22` 由
oracle `route_cases.py` 的构造规则（BASELINE 常量 + `:p0-v2-runtime-source`
派生 32 位 marker）程序化重建（测试内 `SubmissionTestHarness.goldenRuntimeSource()`，
只出现在内存，不落测试资源、不落证据；敏感扫描见 README.md）。
