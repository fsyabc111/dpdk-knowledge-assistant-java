# J2-05 golden 差分说明

来源：`migration/fixtures/p0-v2/python-golden.json` 中本卡 12 条路由共 **33 条
observation**，已提取为 `backend-java/src/test/resources/migration/J2-05/golden-j205.json`
（基线 `p0-v2` / baseline_id 同源）。全部 33 条在六个测试类中以 **整响应体逐字段
断言**（Jackson 树等价：对象键序无关、数值/布尔/空值类型严格）执行，全部通过。

## 归一化映射（动态值 → 测试种子）

| golden 占位/动态字段 | 测试侧替换 | 说明 |
|---|---|---|
| `<identity:50>` | `sess_qdns2xx01` | DNS 解析什么（列表第 1 行） |
| `<identity:40>` | `sess_goldendel01`（生命周期类）/ `sess_qdnsxxx01`（查询类） | DELETE::success 目标 / 列表第 2 行 |
| `<identity:22>` | `sess_qexpl001` | 作业讲解会话 |
| `<identity:6>` | `sess_qunans001` | 合成未解答 |
| `<identity:3>` | `sess_hist` / `SESS_HIST=sess_qhist001` | 合成历史（历史回读目标） |
| `<identity:2>` | `msg_hist_01` | 历史消息 id |
| `updated_at`/`created_at` 微秒 | 种子固定 `T0=2026-10-24T04:00:00Z` + 显式微秒偏移（.000010/.000011/.000019/.000069/.000094/.000103） | 与 oracle 观察序列同值，`str(datetime)` 形态（空格分隔、6 位微数、+00:00）字节一致 |
| `since`（cost） | TestClock 墙钟设为 `T0+31000ns` → since=`2026-10-17T04:00:00.000031+00:00` | `now - 7d` 的 isoformat 与 oracle 同值 |
| `cutoff`（purge） | TestClock 墙钟设为 `T0+32000ns` → cutoff=`2026-09-24T04:00:00.000032+00:00` | 同上 |

时间渲染经 `session.SessionTime`（str(datetime) 与 isoformat 两种形态；读取
timestamptz 时统一归一到 UTC 再渲染——冻结源测试环境会话时区为 UTC，Java 侧
显式归一避免依赖连接时区，差异方向为确定性增强，已在类注释登记）。

## 唯一非逐字段断言：storage_bytes（物理字段）

`GET /api/internal/session-storage::success` 的 golden `storage_bytes=147456`
是 `pg_total_relation_size` 的物理值，依赖 oracle 种子 SQL 的精确页布局。
单卡测试断言：`session_count=2`（精确等于 golden）、`database_backend=postgres`、
`storage_bytes>0`；字节级数值由集成者在 P2 差分 suite（同种子同库布局）统一比对。
其余字段（dry_run/retention_days/cutoff/sessions/messages/citations）全部精确比对。

## 逐字段通过的 33 条清单

- `GET /api/chat/sessions`：success / unauthorized / invalid-input（422 detail 数组含 ctx.ge）
- `GET /api/chat/sessions/{session_id}`：success（消息/引用/metadata/confidence/truncated）/ unauthorized / invalid-input / foreign-tree（404）
- `DELETE /api/chat/sessions/{session_id}`：success（204 + 级联后核）/ unauthorized / invalid-resource
- `GET /api/usage/me`：success（零填充 7 天、quota 10000）/ unauthorized
- `GET /api/usage/subjects`：success（8 主体全零、subject 升序）/ unauthorized
- `GET /api/usage/cost`：success（空账本：token 全 null 非 0、operations.priced=false、row_limit=20000、since 微秒同值）/ unauthorized / invalid-input
- `GET /api/sources/{chunk_id}`：success（含 metadata {}）/ unauthorized / invalid-resource
- `POST /api/plugin-template`：success（**template 文本逐字节等于 golden**；explanation/checklist/references 逐字段）/ unauthorized / invalid-input（missing protocol）
- `GET /api/internal/session-storage`：success（见上归一化）/ unauthorized（403）
- `POST /api/internal/purge-sessions`：success / unauthorized（403）/ invalid-input（retention_days=0）
- `GET /api/grading/submissions`：success（空列表）/ unauthorized / invalid-input
- `GET /api/grading/summary`：success（空聚合）/ unauthorized

## 关键行为口径（与冻结源核对点）

- 求值序：认证 401 → 查询参数 422 → 门禁 404 → 业务（FastAPI solve_dependencies：
  Depends 子依赖先于参数校验；internal 维护端点无认证依赖，参数 422 先于 key 403/503）。
- 模板文本由 Python 源 f-string 逐行提取（续行缩进 32/28/5/4 空格），
  `String.join` 显式构造；测试以 golden 字节断言闭环。
- `usage/cost` 空账本：totals 的 prompt/completion/total/duration 均为 **null 非 0**；
  unknown 与 zero-call 分列；成本按币种分列不跨币种求和。
- 422 query/detail 数组形态（type/loc/msg/input/ctx）与 J2-01 PydanticBody 探针固化一致。
