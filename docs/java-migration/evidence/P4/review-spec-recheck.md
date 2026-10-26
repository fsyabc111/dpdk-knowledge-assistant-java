# P4 规格轴修复复审

固定快照 `22c87e4da031bfe64136898cb9f9156a1078e4d6`；已核实 ref 与 `69dad0f...22c87e4` 差分。原审查报告保留。业务只读；本次未运行 PG、构建或测试，未提交推送。

**结论：SP-P4-01 可关闭；修复未发现新规格阻断。** 规格轴支持进入工程门禁汇总，最终仍由主 Agent 汇合规范轴与当前全量/native CLI结果；不代签用户验收。

`knowledge/workflow/WorkflowBody.java:17-32` 改用显式 JsonParser 读取首文档并核对消费位置，只允许JSON合法尾随空白；任何后续对象、标量或垃圾均在业务调用前返回422。错误保留 `json_invalid`、`Extra data` 及位置；`codePointCount` 将含emoji的Java字符位置转换为Python码点索引。截断首文档仍由解析异常路径拒绝，没有引入忽略尾部的回退。

已独立检查 `WorkflowTrailingJsonTest.java`、`WorkflowTrailingJsonPgTest.java`、四例 `json-trailing-golden.json` 及其Python `json.loads`生成器：第二对象、垃圾、空白后对象、尾随标量逐字段比较422；真实控制器与PG路径拒绝四种非法POST且知识/修订均零行；合法published/shared_confirmed加尾空白成功各一行；非法PATCH不增加修订。该PG测试使用MockMvc驱动真实控制器，不冒称另启网络HTTP。收到的定向执行结果为3/3通过，本审查没有重跑；合法fixture从不允许的draft改成published符合冻结枚举，没有放宽被审查断言。

同时浏览索引公开PublishedKnowledgeReader的配套差分：查询仍限定published并按id排序，完整扫描仍在其前读取expectedActive，知识正文/metadata/切块与CAS语义保持；该规范轴修复未见引入新的规格问题，规范关闭结论仍由另一轴独立签署。

P5严格全路由错误矩阵、网络HTTP/浏览器、CORS及部署恢复等既定保留项继续保持独立，不据此重新阻断本次修复。
