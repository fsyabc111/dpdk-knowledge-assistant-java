# 协议解析引擎模块

## 概述

协议解析引擎模块提供了一个插件化的协议解析框架，支持动态注册和链式处理多种网络协议。

## 核心概念

### 1. 引擎注册中心 (Engine Registry)

引擎注册中心负责管理所有已注册的协议解析引擎，维护引擎链表并协调数据包的处理流程。

### 2. 引擎接口 (Engine Ops)

每个协议解析引擎必须实现统一的 `engine_ops_t` 接口，包括：
- `init()` - 初始化引擎
- `can_handle()` - 判断是否能处理数据包
- `process()` - 处理数据包并提取协议特征
- `cleanup()` - 清理资源

### 3. 链式处理

引擎按优先级排序形成处理链，数据包依次经过每个引擎：
- 优先级越小越先执行
- 引擎可返回 `ENGINE_HANDLED` 停止后续处理
- 引擎可返回 `ENGINE_CONTINUE` 继续下一个引擎

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│              引擎注册中心 (Registry)                      │
│                                                         │
│  engines[]:  [HTTP] [HTTPS] [DNS] [QUIC] ...           │
│                                                         │
│  chain_head: HTTP → HTTPS → DNS → QUIC → ...           │
│              (按优先级排序)                              │
└─────────────────────────────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────┐
              │  数据包到达       │
              └────────┬─────────┘
                       │
                       ▼
         ┌─────────────────────────┐
         │  遍历引擎链               │
         └────────┬────────────────┘
                  │
      ┌───────────┴───────────┐
      │                       │
      ▼                       ▼
┌──────────┐          ┌──────────────┐
│can_handle│  YES     │   process()  │
│   ?      │─────────►│              │
└────┬─────┘          └──────┬───────┘
     │ NO                    │
     │                       ▼
     │              ┌─────────────────┐
     │              │ HANDLED/CONTINUE│
     │              └────────┬────────┘
     │                       │
     └───────────────────────┘
```

## 如何设计引擎

### 步骤 1: 定义引擎结构

```c
// 引擎私有数据
typedef struct my_engine_data {
    uint64_t packet_count;
    // 其他私有字段
} my_engine_data_t;
```

### 步骤 2: 实现核心接口

```c
// 初始化
static int my_engine_init(engine_ctx_t *ctx)
{
    my_engine_data_t *data = calloc(1, sizeof(my_engine_data_t));
    if (!data) return ENGINE_ERROR;
    
    ctx->private_data = data;
    return ENGINE_OK;
}

// 判断是否能处理
static bool my_engine_can_handle(engine_ctx_t *ctx,
                                  const session_entry_t *session,
                                  const parsed_packet_t *pkt)
{
    // 示例: 检查端口号
    return (pkt->protocol == 6 && pkt->dst_port == 8080);
}

// 处理数据包
static int my_engine_process(engine_ctx_t *ctx,
                              session_entry_t *session,
                              const parsed_packet_t *pkt)
{
    my_engine_data_t *data = ctx->private_data;
    
    // 解析协议特征
    // 例如: 提取 HTTP 头部、DNS 查询等
    
    // 更新会话信息
    // session->ext_data = ...
    
    data->packet_count++;
    
    return ENGINE_HANDLED;  // 或 ENGINE_CONTINUE
}

// 清理资源
static void my_engine_cleanup(engine_ctx_t *ctx)
{
    if (ctx->private_data) {
        free(ctx->private_data);
        ctx->private_data = NULL;
    }
}
```

### 步骤 3: 定义引擎操作表

```c
const engine_ops_t my_engine_ops = {
    .name = "MyProtocol",
    .protocol_id = 1001,
    .priority = 100,
    .init = my_engine_init,
    .can_handle = my_engine_can_handle,
    .process = my_engine_process,
    .cleanup = my_engine_cleanup,
    .get_stats = NULL,      // 可选
    .reset_stats = NULL     // 可选
};
```

### 步骤 4: 注册引擎

```c
engine_registry_t *registry = engine_registry_create();
engine_registry_register(registry, &my_engine_ops);
```

## 内置引擎示例

### HTTP 引擎

- **优先级**: 100
- **检测条件**: TCP && (port 80 || port 8080)
- **提取信息**: Method, URL, Host, User-Agent

### HTTPS/TLS 引擎

- **优先级**: 110
- **检测条件**: TCP && port 443 || TLS握手
- **提取信息**: SNI, TLS版本, Cipher Suites

### DNS 引擎

- **优先级**: 120
- **检测条件**: UDP && port 53
- **提取信息**: Query Name, Query Type, Response

## 使用示例

```c
// 创建注册中心
engine_registry_t *registry = engine_registry_create();

// 注册引擎
engine_registry_register(registry, &http_engine_ops);
engine_registry_register(registry, &https_engine_ops);
engine_registry_register(registry, &dns_engine_ops);

// 处理数据包
parsed_packet_t pkt;
session_entry_t *session;

// ... 解析数据包 ...

engine_process(registry, session, &pkt);

// 清理
engine_registry_destroy(registry);
```

## 引擎输入输出规范

### 输入

**parsed_packet_t** - 已解析的数据包结构：
- L2: eth_type
- L3: src_ip, dst_ip, protocol
- L4: src_port, dst_port, tcp_flags
- Payload: payload指针, payload_len

**session_entry_t** - 会话信息（可选）

### 输出

引擎应该：
1. 提取协议特征信息
2. 通过 `session->ext_data` 存储特征
3. 返回处理结果：
   - `ENGINE_HANDLED` - 已处理，停止链
   - `ENGINE_CONTINUE` - 继续下一个引擎
   - `ENGINE_ERROR` - 处理错误

## 最佳实践

1. **优先级设置**
   - 基础协议优先级低 (100-199)
   - 应用层协议优先级中 (200-299)
   - 自定义协议优先级高 (300+)

2. **性能优化**
   - `can_handle()` 应快速返回
   - 避免在 `process()` 中进行重复解析
   - 使用引擎私有数据缓存状态

3. **错误处理**
   - 验证输入参数
   - 处理异常数据包
   - 记录错误统计

4. **内存管理**
   - 在 `init()` 中分配私有数据
   - 在 `cleanup()` 中释放资源
   - 避免内存泄漏

## 文件结构

```
engine/
├── engine.h              # 接口定义
├── engine_registry.c     # 注册中心实现
├── engine_http.c         # HTTP 引擎 (待实现)
├── engine_https.c        # HTTPS 引擎 (待实现)
├── engine_dns.c          # DNS 引擎 (待实现)
├── engine_quic.c         # QUIC 引擎 (待实现)
└── README.md             # 本文档
```

## 扩展性

引擎框架支持：
- 动态注册/注销引擎
- 运行时调整优先级
- 引擎统计信息收集
- 自定义协议解析

## 注意事项

1. 引擎必须是线程安全的（如果多线程使用）
2. 不要在引擎中阻塞太久
3. 合理使用 `ENGINE_HANDLED` 避免不必要的处理
4. 定期检查和更新引擎统计信息
