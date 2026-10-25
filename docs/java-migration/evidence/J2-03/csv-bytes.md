# J2-03 CSV 原始字节摘要及解析（验收 G）

载体：CsvExportContractTest（真 PG + MockMvc；响应字节直接断言，不经字符串解码）。

## golden 字节级一致（teacher_a 视角，AssignmentReportContractTest 同树还原）

前置：作业（title 合成DNS作业修订、items [dns 20]）+ 成绩行（U20270000011、dns 80、created_at=2026-10-24T04:00:00.000060Z、assignment_id 指向该作业）。

- **length = 163**、**sha256 = f280bec9cd2ffed50320aac7ea4f89ca5561351eef11268c04acdcc4277da763**（与 golden `GET /api/assignments/{assignment_id}/export::success` 的 body 完全一致）。
- 原始字节（hex 前缀 `EF BB BF`=UTF-8 BOM）：

```
\ufeff班级(老师学号),学生学号,dns(满分20),总分,提交次数,最近提交时间\r\n
U20270000002,U20270000011,16.0,16.0,1,2026-10-24 04:00:00.000060+00:00\r\n
```

- 头部字段：`Content-Type: text/csv; charset=utf-8-sig`（逐字节）、`Content-Disposition: attachment; filename="assignment_{id}_{csvSafe(subject)}.csv"`。

## 字节级规则验证（bomCrLfAndValueRendering）

- BOM：首三字节 EF BB BF。
- 行结束：CRLF（csv.writer excel 方言）；表头无引号（QUOTE_MINIMAL：中文/括号不触发转义，全文无 `"`）。
- 值渲染：dns 0 分 → `0.0`；http 未提交 → 空串（`,,`）；总分 0.0 → `0.0`；次数 int；时间 Python str(datetime)（空格分隔+微秒6位+00:00）。
- 已不在当前项内的协议（ssh）提交：计入次数/最近时间，不产生 score 单元格。

## 分组解析（leaderGroupedByClassWithBlankLines / admin / deleted）

- leader/operator/静态 admin：表头 + 班级组（组间恰一个空行=CRLF），班级名升序（未分配中文「未分配」最后）。实测三组 `[A, B, 未分配]`、空行位置 2 处、行序与 user_id 升序一致。
- teacher（admin）：单组无空行；文件名含老师学号（csvSafe：`[^A-Za-z0-9_.-]+→_`）。
- 账号删除：该行归「未分配」组，学号/成绩保留（16.0/8.0 两组内共存）。
- 错误分支：p0-missing → 404；viewer → 404；未认证 → 401。
