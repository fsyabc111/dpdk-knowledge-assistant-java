# J0-02 实测差异与限制

这些条目是冻结Python的实际行为，未修改源码，不代表用户已决定Java采用何种取舍。

| ID | 触发与实测 | 来源 | 当前oracle处理 |
|---|---|---|---|
| ORACLE-D01 | 首次回答有citation.title/score和next_steps；同request_id重放将title置空、score置0、next_steps=[] | `backend/app/services/rag_product_service.py:217` | 首次与重放分别完整采集；只额外断言answer/身份/node顺序与零新增上游调用，不抹去这些字段 |
| ORACLE-D02 | DELETE会话后rag_requests.session_id/message_id仍保留，关联消息已删除 | `backend/app/services/session_service.py:318` | 仅本脚本显式删除的chat task允许悬挂；其他关联缺失失败 |
| ORACLE-D03 | 常规success SSE done无terminal；终态行重建附true；pending帧为status=pending/terminal=false | `backend/app/services/task_service.py:208`、`:224`、`:325` | 三种原形分别比较；pending绝不能误认为终态 |
| ORACLE-D04 | PG实例默认时区可能为Asia/Taipei，str(TIMESTAMPTZ)呈+08:00 | 环境现场观测 | 合成功能连接显式PGOPTIONS timezone UTC，不修改服务器全局设置；黄金保留UTC原字符串 |
| ORACLE-D05 | 合法grade ID包含连续≥7位数字时，redact将这段数字省略，可能使两次随机fixture文本长度不同 | `backend/app/services/rag_issue_service.py:31` | Java默认保留现有算法；v2用合法确定grading UUID端口稳定其他用例，并将含/不含连续数字的两个固定向量逐值加入可比较trace；不删字节数/字面指纹/引用断言。将来避免ID遮蔽另案决定 |
| ORACLE-L01 | account模式基础路由覆盖；local/token仅最小auth向量 | route-case-map.json | 不宣称所有认证模式×角色×路由组合通过；对应业务卡/J5-01补齐 |
| ORACLE-L02 | 28条有写入/状态语义路由尚未执行完整重试或并发分支 | route-case-map.json | 明确未执行；正式submit无业务幂等键，不能扩展chat/job去重承诺 |
| ORACLE-L03（已补验） | v1 auth停用样例使用已logout token；现已独立验证新token有效→停用无效→启用旧token不复活→新token有效 | check_account_disable.py / auto/account-disable-followup.json | 四项真实PG断言通过；历史v1缺口保留为历史，不再属于当前未执行项 |
| ORACLE-L04 | 空库当前schema29表已核验；未构造真实schema0013合成升级库 | safety.py / runtime.py | 不用downgrade版本号伪造0013升级；交J1-01/J5迁移演练 |
| ORACLE-L05（已修） | v1只有计数；v2已加入25表白名单业务初态、逐case实际行delta与完整终态，并验证可重建 | business_state.py / p0-v2/business-projection.json | 同HTTP/行数的扣次、score、job、权限、费用/NULL和引用错误实际CLI均拒绝 |

COMP003已经用户接受为Java有意安全差异（主Agent记录U08/P0-D13）：running仅等/查且受硬墙；unknown/查询失败不重投，system_error+failure_code，保留submitted账本且终态清输入。安全目标独立保存在 `migration/fixtures/p0-approved-differences/comp003.json`，尚无Java执行证据。Python黄金保持如实行为，不能以自对照通过替代这个安全目标。
