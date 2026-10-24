# HTTP、错误、CSV 与 SSE

路由的完整签名、响应模型、局部 HTTPException 表达式和 return 分支在 `coverage-map.json.routes[]`，与 `static-inventory.json.routes[]` 同主键；源码行号是可追溯规范。全模块 helper 的错误收录在 `function-catalog.json`，用于审查转抛/错误优先级；静态调用图不能证明动态路径穷尽，最终响应字节/状态以 J0-02 合成 fixture 冻结。Java 不直接套用 Spring 默认 ProblemDetail、错误状态或 nullable 序列化。

## 请求与错误

- 常规 FastAPI/Pydantic 参数错误为 422 的 `{"detail":[...]}`；HTTPException 为 `{"detail":任意既有detail}`，保留 Retry-After、WWW-Authenticate 等头。500/405、框架文档面和 CORS 的精确格式由同版本框架 oracle 固定。不要把所有错误 detail 压为字符串。
- JSON chat/submit 是手工 Request JSON 解析：非法 JSON/非 UTF-8 返回 422 `request body must be valid JSON`；手工 Pydantic ValidationError 使用 `errors(include_url=False,include_context=False)`，与默认 body 解析的 loc 细节不可假定一样。JSON message 不自动 strip；multipart message/session_id/protocol 会 strip。
- chat multipart：message/session_id/context/request_id/file；context 必须 JSON object，否则 400；扩展名仅 .c/.so（不区分大小写），原件大于 grading_max_code_bytes 为 413。.c 用 UTF-8 replacement 解码，.so 不作为 chat 执行源码；有附件无 message 自动生成请求文案。见 `api/chat.py:39`。
- 正式 submit multipart：protocol 必填，file 必填，允许 .c/.so；两者都 UTF-8 replacement 解码给评测。JSON 需 protocol/code。超大 413、配额 429、评测超时 504、其余 harness 错误 502，detail 保留。正式 submit 没有客户端业务幂等键（见差异账本）。见 `api/assignments.py:272`、`:311`。
- maintenance key 未配置 503，缺失/不符 403；隐藏维护端点不走普通 bearer。purge 默认 dry_run=true，retention_days 缺省取配置；read storage 不清理。见 `api/chat.py:304`。
- 管理/作业/成绩/问题工作台的错误优先级受依赖与手工守卫位置影响，不能泛化为统一403。认证、请求体不合法、账号停用、目标越权组合必须由 fixture 覆盖。
- 容量排队、日配额、每分钟限额各自独立；任务 replay/attach 不占执行槽、不重复日配额，但路由入口分钟限额仍执行。日计数按 UTC date；daily_quota=0 为不限额。正式评测配额与 chat 计数以 kind 分开。

## SSE

唯一线格式：`event: <name>\ndata: <json>\n\n`；JSON 使用 ensure_ascii=false，响应 `Content-Type:text/event-stream`、`Cache-Control:no-cache`、`X-Accel-Buffering:no`。TCP read chunk 不等于事件边界，消费者必须逐行/空行解析，可跨 UTF-8 字节边界。框架可能补 charset，fixture 以真实响应为准。源：`api/chat.py:178`、`api/tasks.py:79`。

| event | data 契约 | 来源 |
|---|---|---|
| start | session_id、request_id、task_id；某些终态缺 session 可以 null | `task_service.py:199` |
| status | 至少 stage，空闲等待每 1 秒 `stage=processing`；工具进度保留额外字段 | `task_service.py:244` |
| tool / 其他进度 | 由 product/Agent 实际 emit 冻结；不能只允许三种终态帧 | `rag_product_service.py:_candidate` / `task_service.py:ManagedTask` |
| sources | citations 数组，字段与 Citation schema 一致 | `task_service.py:208` |
| delta | delta=正文；持久终态重放可以一次发完整正文 | `task_service.py:208` |
| done | ChatResponse 去 answer/citations，终态 DB 重建额外 status/terminal=true；不可恢复的 pending 分支仅 task_id/status=pending/terminal=false | `task_service.py:225`、`:304` |
| error | message=`服务暂时不可用，请稍后重试`、retryable=true；流开始后出错用事件，不能再改 HTTP 状态 | `api/tasks.py:79`、`api/chat.py:178` |

断线只移除订阅者，不取消后台任务。显式 cancel 和停号才取消；重复 cancel/终态返回现状。task events GET 的 pending 恢复会触发副作用，不能视为纯读。内存进度日志最多500，丢最旧；终态注册表保留300秒；恢复 grace 30秒、心跳10秒。重连回放不实现 Last-Event-ID 游标或逐帧 exactly-once，前端允许重复进度和整段正文。源：`task_service.py:37`—`:41`、`:244`、`:304`、`:452`。

引用必须按提供给模型的 chunks 顺序一一绑定；同文件不同 chunk 各占编号，score 按 Python round(...,4)。持久 citation 的 engine+chunk_id 读回历史节点，release 切换不应让旧引用漂移。源：`citation_service.py:9`、`rag_issue_service.py:383`、`api/chat.py:188`。

## CSV

UTF-8 BOM，Python csv.writer 的 CRLF 和引号规则，`text/csv; charset=utf-8-sig`，Content-Disposition 由安全文件名生成。列顺序是班级/学号、作业 items 顺序的协议分值、总分/提交次数/最近时间；未提交为 `""`，未分配班级为“未分配”。admin 一班，leader/operator/静态主体按班级分组（组间空行，未分配最后）。源：`api/assignments.py:532`。精确 header/浮点文本由 fixtures 固定。

首次回答与持久终态重放的 citations.title/score 和 next_steps 按当前源码可能不同：finish.evidence_json只保留chunk_id/source_path/section，chat_response_from_row重建score=0/title默认空串/next_steps=[]（COMP-010）。对照应分别冻结首次/重放路径，不能用first==replay全字段相等代替兼容判断。会话history另从assistant_citations回读，亦需独立fixture。
