# J2-03 多班级角色结果（验收 A/B/F）

树：leader → teacher A（学生 A1/A2）/ teacher B（学生 B1）；未分配学生 FREE；静态 token：viewer / operator / admin(t-teacher)。
载体：AssignmentCatalogContractTest + AssignmentReportContractTest + CsvExportContractTest（真 PG + MockMvc standalone）。

## 端点 × 角色矩阵（实测状态码）

| 端点 | leader | operator（账号/静态） | admin（老师） | viewer（账号/静态） | 未认证 |
|---|---|---|---|---|---|
| GET /api/assignments | 200（全部，manager 分支） | 200（全部） | 200（仅自建） | 200（学生分支：本班+all） | 401 |
| GET /api/assignments/protocols | 200 | **404** | 200 | 404 | 401 |
| POST /api/assignments | 201（scope=all） | 404（先于 422） | 201（scope=class） | 404 | 401 |
| GET /{id} | 200 | 200（不受限） | 200 仅自建，否则 404 | 学生：可见 200 / 不可见 404 | 401 |
| PATCH /{id} | 200（任意） | 404 | 200 仅创建者；他人 403「只有作业创建者或组长可以修改」 | 404 | 401 |
| POST /{id}/withdraw、/reactivate | 200 | 404 | 200 仅创建者/leader；他人 403 | 404 | 401 |
| GET /{id}/grades | 200（全部） | 200（全部） | 200（仅本班行） | 404 | 401 |
| GET /{id}/export | 200（分组） | 200（分组） | 200（本班单组） | 404 | 401 |

非 account（local）模式：上述全部端点整组 404（AssignmentCatalogContractTest.nonAccountModeHidesWholeGroup，独立 local 库验证）。

## 学生可见性（scope 与班级）

| 主体 | 可见集合（实测） |
|---|---|
| A1（teacher A 班） | teacher A 的 class 作业 + 全部 all 作业 |
| B1（teacher B 班） | 仅 all 作业；teacher A 的 class 作业详情 404（golden foreign-tree 同口径） |
| FREE（created_by NULL） | 仅 all 作业 |
| 撤回后 | 学生列表不含、详情 404；reactivate 后恢复可见 |

## 权限与 scope 服务端产生

- create：scope 由服务端按发布者角色写入（leader→all、admin→class）；请求体无 scope 字段（schema 不存在该字段，客户端不可指定）。
- 修改/撤回权限 = 创建者本人或 leader；operator 无修改权（只读成绩/导出）。
- golden POST/PATCH 响应的 scope/created_by/status 逐值断言。

## 成绩树隔离与账号删除（验收 F）

- teacher A：rows 仅 A1/A2（user_id 升序，class_teacher=A）；teacher B：仅 B1（对他人作业的 grades 门禁按角色，不按创建者——与源一致）。
- leader/operator/静态 admin：全部行；FREE 行 class_teacher=null。
- 删除 A1 账号（含其 api_tokens）：grades 行保留、user_id 保留、class_teacher=null；CSV 归「未分配」组（16.0 保留）。
