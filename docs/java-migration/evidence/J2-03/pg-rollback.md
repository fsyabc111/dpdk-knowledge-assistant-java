# J2-03 PG 更新回滚记录（验收 C：协议项替换事务原子）

载体：AssignmentCatalogContractTest.duplicateProtocolRejectedAndTransactionRolledBack（真 PG，非 mock repository）。

## 场景

PATCH `/api/assignments/{aid}`（创建者本人），body 含 `title=不应生效` 与重复协议项 `[{dns,10},{dns,20}]`。

## 期望与实测

| 项 | 结果 |
|---|---|
| 请求结局 | 存储失败终止（MockMvc 抛 NestedServletException；oracle 侧为未捕获 UniqueViolation → 500 通用体，fixture 无该 case，不逐字节断言 body） |
| 触发约束 | `assignment_items UNIQUE(assignment_id, protocol)`（B0015 DDL；Python 同样不预校验重复协议） |
| assignments.title | **仍为「作业一」**（同事务的 UPDATE 已回滚） |
| assignment_items 行数 | **仍为 2**（dns/http 原项保留；DELETE+INSERT 序列整体回滚） |

## 机制

`AssignmentCatalogRepository.updateAssignment` 在单笔 `TransactionRunner.required` 内执行：SELECT created_by → 权限检查 → UPDATE assignments（含 updated_at）→ 可选 `_replace_items_sync` 同构（DELETE 全部 + 逐项 INSERT）。任一步 SQLException → runner 回滚整笔（与 Python `with get_conn()` 事务形状一致）。创建路径同构（INSERT 主行 + items 同事务）。

权限失败（AssignmentPermissionException）在 UPDATE 之前抛出，天然无部分写入；403 响应与库内零变更已由 updatePermissionAndItemsReplace 断言。
