# J2-03 golden 差分（28 条 observation）

来源：`migration/fixtures/p0-v2/python-golden.json` 中 path 前缀 `/api/assignments` 且不含 `/submit`、`/explain`（正式提交与讲解归 J2-04）的全部 observation，按 fixture 执行顺序提取（28 条）。
断言载体：三个契约测试类内嵌（与 J2-02 同模式），比对用 Jackson JsonNode 树等（对象字段序不敏感；CSV 按原始字节）。

## 归一化映射（动态值）

| 动态字段 | 处理 | 依据 |
|---|---|---|
| `id`（asg_ 16 hex） | 实际值 ↔ `<identity:19>` 双向替换后比对；格式独立断言 `^asg_[a-f0-9]{16}$` | 全 trace 双射规则（typed_identity） |
| `assignment.created_at` / `updated_at` | 正则 `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{6})?\+00:00$` 校验形态后置 `<normalized>` | oracle 微秒为进程级顺序时钟产物（FixedDatetime logical tick），Java 侧 TestClock 等价严格递增微秒，数值不可跨进程复现（J2-02 golden-diff 同结论） |
| 其余全部字段（含 `deadline`、`last_submitted_at`、全部错误 detail） | **逐值精确比对，零归一化** | deadline 为固定 2099 值；last_submitted_at 用合成成绩行 created_at=oracle tick 60（2026-10-24T04:00:00.000060Z）还原为逐字节 |

## 逐条结果

| case_id | 结果 | 备注 |
|---|---|---|
| GET /api/assignments::success | 零差异 | leader 空态 `{"assignments":[]}` |
| GET /api/assignments/protocols::success | 零差异 | harness 替身按 oracle 固定脚本只回 dns；响应 `error:null`、categories int |
| POST /api/assignments::success | 归一化后相等 | 201；scope=class 服务端产生；deadline="2099-01-01 00:00:00+00:00" 逐字节 |
| GET /{assignment_id}::success | 归一化后相等 | student_a 视角；未提交协议 best_score/earned=null |
| PATCH /{assignment_id}::success | 归一化后相等 | title 修订 |
| GET ::success-nonempty-manager | 归一化后相等 | submission_count=1、student_count=1（合成成绩行） |
| GET ::success-nonempty-student | 归一化后相等 | best 80 / earned 16.0 / submission_count 1 |
| GET /{id}/grades::success | 归一化后相等（仅 assignment 时间字段） | 行内 last_submitted_at="2026-10-24 04:00:00.000060+00:00" 逐字节 |
| GET /{id}/export::success | **原始字节摘要逐字节一致** | length=163、sha256=f280bec9…da763（见 csv-bytes.md） |
| withdraw/reactivate ::success ×2 | 归一化后相等 | status 翻转 |
| ::unauthorized ×7（list/protocols/{id}/PATCH/withdraw/reactivate/grades/export） | 零差异 | 401 `{"detail":"missing or malformed bearer token"}` |
| POST ::invalid-input | 零差异 | 422 三条 missing（title/deadline/items 字段定义序，input={}） |
| PATCH ::invalid-input | 零差异 | 422 string_too_short（ctx.min_length=1，msg 单数 "character"） |
| ::invalid-resource ×4（{id}/grades/export/withdraw/reactivate） | 零差异 | 404 `{"detail":"not found"}`（p0-missing） |
| GET /{id}::foreign-tree | 零差异 | student_b 别班 → 同口径 404 |

## 有意差异 / 非逐字节断言登记

1. **未覆盖分支按惯例输出**（fixture 无对应 observation，非破坏）：deadline 非字符串/非法串（datetime_parsing/datetime_type 文案）、items 嵌套 loc（`["body","items",0,"protocol"]`）、points 边界 greater_than_equal(ge=1)/less_than_equal(le=1000)、int 字符串拒绝（沿用 J2-02 AdminPydanticBody 口径：pydantic v2 lax 会把 "20" 转 20，Java 拒绝——已在 J2-02 登记过的同族差异）、空 body 的 loc=["body"] missing。
2. **重复协议**：Pydantic 不校验重复，PG `assignment_items UNIQUE(assignment_id,protocol)` 拒绝 → Python 未捕获异常为 500 通用体；Java 侧为 IllegalStateException 透传（容器 500），测试断言「存储失败 + 整事务回滚」，不逐字节断言 500 body（fixture 无该 case）。见 pg-rollback.md。
3. **CSV Content-Type**：`text/csv; charset=utf-8-sig` 中 `utf-8-sig` 不是 JVM 注册字符集，Spring MediaType 解析拒绝；导出端点直接 `HttpServletResponse.setHeader` 写原始头（不经 HttpEntity 转换器），线上与 MockMvc 均得到逐字节一致的 Content-Type。功能等价，实现路径登记。
4. **protocols 数值域**：Python `int(item.get("cases") or 0)` 对非数值串会抛（500）；Java 按数值取 int、否则 0。harness 为受控组件，登记为不可达分支。
5. **求值顺序**：assignments.py 与 admin.py 不同——守卫是依赖（401 → 404 门禁 → 422 体），源注释显式声明；Java 控制器按此序实现并用 operator+空体=404（非 422）断言。
