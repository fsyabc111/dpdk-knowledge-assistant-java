# 插件接口与生命周期：从加载到处理报文

本项目通过操作表与注册中心调度协议插件。理解生命周期时，应分别检查加载成功、进入处理链、返回值控制和资源清理；这四步并非同一个状态。本篇解释已提交源码的行为，不提供完整插件答案。

## 适用版本与阅读目标

本文面向懂 C 和网络基础、初识项目的学生，依据 DPDK 仓库 `20f882691ac03998e0e148e0a66041227cd3f751`。状态：**待人工审核**。本文只做静态源码核查，没有编译、抓包或测试插件。

读完应能沿源码说明一个插件为何被加载、何时获得报文、何时退出处理链。这里讲的是本项目的引擎接口，不是所有 DPDK 应用的通用插件规范。逐项依据见[插件事实表](../evidence/plugin_lifecycle.md)，与抓包的衔接见[报文主流程](packet_flow.md)。

## 先区分接口、实例与会话

`engine_ops_t` 是操作表，保存名称、协议 ID、优先级和函数指针；`engine_ctx_t` 保存某个引擎条目的私有数据指针与统计。`engine_registry_t` 则管理条目数组、按优先级排列的链和动态库句柄列表。接口定义不等于已创建实例：注册过程会分配 entry，并在 entry 内保存独立的 ctx。

会话 `session_entry_t` 由会话模块管理；`parsed_packet_t` 是本次报文的解析结果。插件收到这两个指针，并不因此拥有报文缓冲区。通用 C 原则是：保存一个指针不会复制其指向的数据。本项目还需要检查对应数据实际在哪里释放。

来源：`plugin/engine.h` 的结构体与 `engine_registry_register`；事实 PL-001、PL-002、PL-009。

## 从加载到进入处理链

```mermaid
flowchart LR
    A[加载动态库] --> B[取得 engine_get_ops]
    B --> C[检查名称与容量]
    C --> D[创建 entry 并调用可选 init]
    D --> E[成功后插入优先级链并登记句柄]
    E --> F[engine_process 调度]
    F --> G[显式卸载或注册中心销毁]
```

这张图描述成功路径。`engine_registry_load_plugin` 调用 `dlopen`、查找导出函数 `engine_get_ops`，再取得操作表。重复名称、容量不足或初始化失败会返回错误。init 非空时必须返回 `ENGINE_OK` 才继续注册。

init 失败分支释放 entry 和动态库句柄，**没有调用 cleanup**；所以不能依赖注册中心替失败的 init 回收所有部分初始化资源。另一个分支不同：init 成功后若分配句柄记录失败，代码会撤销注册并调用 cleanup。失败位置决定清理行为。

启动时 `load_plugins_to_registry` 遍历固定列表，跳过不存在的文件；`--no-plugins` 会跳过此加载函数。注册中心条目没有统一的 enabled 字段，因此排错时应检查是否实际注册，不能假定另有通用启用开关。还要限定 `--no-plugins` 的作用范围：该标志在 `main.c` 中只被这一个启动加载函数读取，运行时插件控制入口不检查它——所以它关闭的是启动期加载，不是后续热加载能力。

来源：`plugin/engine_registry.c` 的加载函数、`main.c` 的加载入口与运行时控制入口；事实 PL-003、PL-004、PL-005、PL-013。

## can_handle、process 与返回值

`insert_to_chain` 按 priority 从小到大插入，同值条目排在已有同值之后。每次 `engine_process` 从链头遍历，先增加当前条目的 `packets_processed`，再检查 can_handle：函数指针非空且返回 true，才调用 process。计数增加不代表 process 已执行。

本版本调度处没有先检查 process 指针是否为空；实现插件时应提供与接口匹配的回调，不能把注册成功理解为所有函数指针已获完整校验。

| process 返回值 | 当前调度器的行为 |
| --- | --- |
| `ENGINE_HANDLED` | 增加匹配计数，停止本次链，向调用方返回 `ENGINE_OK` |
| `ENGINE_ERROR` | 增加错误计数，停止本次链并返回错误 |
| 其他值 | 继续后续引擎；`ENGINE_CONTINUE` 表达这一意图 |

头文件定义了 `ENGINE_SKIP`，但调度器没有给它独立分支。不要把未识别的返回值当成已验证的扩展协议。

来源：`plugin/engine_registry.c` 的 `insert_to_chain`、`engine_process`；事实 PL-006、PL-007。

## 多实例、热加载与清理边界

单线程入口创建全局注册中心；DPDK 流水线为每个 worker 创建注册中心和会话表。独立 ctx 不等于动态库的所有全局变量也独立，更不能据此宣布所有插件线程安全。

运行时 load 会收集当前注册中心、检查已加载状态，再逐个加载；中途失败会尝试卸载此前已加载的实例。它不是一次跨 worker 的原子提交。注册/卸载使用写锁，处理链持有读锁；这些锁不能证明插件内部共享数据没有竞争。

显式 `engine_registry_unload_plugin` 移出条目、调用 cleanup、关闭动态库并释放句柄记录。`engine_registry_destroy` 则调用各 entry 的 cleanup 并释放条目和注册中心，但函数体未遍历 plugin_list 关闭动态库，两种清理不能画等号。还有第三种：`engine_registry_unregister` 同样移出条目、调用 cleanup 并释放 entry，但**既不关闭动态库，也不摘除 plugin_list 节点**。判断某个引擎被移走后还剩什么，要先确认走的是这三条路径中的哪一条。

会话模块在过期清扫或销毁时释放会话及所列扩展指针。报文 payload 指向原抓包数据：单线程回调返回后进入 release，worker 处理完后释放 mbuf。跨报文状态应按明确的所有权规则保存，不能长期持有未经复制的 payload。

来源：`main.c`、`plugin/engine_registry.c`、`session/session.c`、抓包 release；事实 PL-008、PL-009、PL-010、PL-012。

## 阅读顺序与自查问题

建议依次读 `engine.h` → 加载与插链 → `engine_process` → main 的调用点 → 卸载与会话清理。遇到“加载成功但没有进入 process”，按事实 PL-004、PL-006、PL-007、PL-011 核对：目标注册中心是否有该插件；main 是否得到会话；前序引擎是否已 HANDLED/ERROR；can_handle 是否存在并返回 true。

自查：为什么 packets_processed 增长而 process 未运行？初始化失败是否一定调用 cleanup？worker 各有 ctx 是否证明整个插件线程安全？这些是读码练习，不是独立验收题。

本文未验证实际热加载并发、插件私有资源完整回收或硬件运行效果；相关风险见证据包，不将静态阅读写成运行保证。
