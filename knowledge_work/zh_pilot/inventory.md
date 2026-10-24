# 现有资料盘点与本批选题

固定源：DPDK `20f882691ac03998e0e148e0a66041227cd3f751`。本批只制作两篇待人工审核样稿，不修改以下资料。快照文件清单与行数见 [来源基线](checks/source_baseline.json)。

| 既有资料 | 已有覆盖 | 本批补充或收窄 |
| --- | --- | --- |
| `plugin/README.md` | 操作表、优先级链、示例实现、开发建议 | 不再复制模板；沿 registry 实现解释 init 失败、返回值分支、process 调用条件与销毁边界 |
| `plugin/PLUGIN_SYSTEM.md` | 动态装载、卸载、热插拔 API | 分开启动加载与跨 worker 管理路径；“不影响其他引擎运行”不扩写成原子切换或无阻塞保证 |
| `capture/README.md` | 后端抽象、单包/批量 API、配置与生命周期提示 | 从 Makefile 和 main 的实际调用说明单线程/worker 区别，不将 API 示例当运行主链路 |
| `session/README.md` | 会话键、哈希表、统计、维护 API | 只讲报文路径何时 lookup/create/update，以及释放边界；不另写第三篇会话教程 |
| `docs/system_design.md` | 系统三层结构、DPDK 多核、存储与管理的大范围说明 | 仅作阅读地图；两篇聚焦采集器局部，避免加入 Web 和数据库部署细节 |
| `docs/codebase_reference.md` | 函数与结构概览、流程图 | 单线程图写 `capture_recv_burst`，当前 main 调 `capture_loop`，内部用 `capture_recv`；样稿按实现收窄 |

## 已核实的差异与写作处置

- `main.c` 的 worker 函数头注释仍写“从私有 worker ring 取包”，函数体直接调 `capture_dpdk_recv_raw`；以函数体及启动代码为准。
- `plugin/engine_registry.c` 的 init 失败分支释放 entry，但没有调用 cleanup；不能把“初始化/清理成对”写成所有失败分支都成立。
- `ENGINE_ERROR` 会终止当前引擎链；除 HANDLED/ERROR 外的返回值落入继续分支。枚举定义不等于独立分支实现。
- 单线程会话统计提交位于插件调用之前；worker 构造存储任务位于插件调用之后。两者分别画图/对照。
- `engine_registry_destroy` 与 `engine_registry_unload_plugin` 的释放范围不同：前者未遍历 plugin_list 关闭句柄。本批只记录函数内事实，不判断整个进程是否实际泄漏。
- 根目录 `capture_dpdk.c` 保留了同名后端源码；本批目标 Makefile 链选用 `capture/capture_dpdk.c`，不判断其他构建入口是否使用根目录副本。

## 不重复建设

插件篇提供“从已加载到未进入 process”的读码路线；报文篇提供“同一报文经过哪些对象、何时释放”的阅读路线。两篇互相引用，不复制完整协议实现、harness 参考答案、通用 DPDK 百科或全部存储架构。

## 来源选择与限制

已导出 22 个已提交文件。`plugin/Makefile` 用于确认引擎库来源，`stats/storage_engine.c` 用于核实 submit 是更新虚拟集合而非直接证明落盘，两者是读码后补充的必要依赖。未读取 harness 教师答案。
`stats/stats.h`、`stats/storage_engine.h`、`capture/capture.h`、`session/session.h` 提供类型/API 背景；不因头文件存在便推断全部运行行为。来源清单中未用于关键事实的文件仅作盘点，不作为隐含的全模块审计。
