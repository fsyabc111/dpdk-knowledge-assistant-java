# 引擎动态插件系统

## 概述

本文档介绍引擎注册中心的动态插件热插拔功能实现。

## 系统架构

```
┌─────────────────────────────────────────────────────────┐
│              引擎注册中心 (Registry)                      │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ 静态引擎      │  │ 动态插件 1    │  │ 动态插件 2    │ │
│  │ (HTTP)       │  │ (DNS.so)     │  │ (QUIC.so)    │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
│                                                         │
│  处理链: HTTP → DNS → QUIC → ...                        │
└─────────────────────────────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────┐
              │  运行时操作       │
              ├──────────────────┤
              │ • 加载插件        │
              │ • 卸载插件        │
              │ • 重新加载        │
              └──────────────────┘
```

## 核心功能

### 1. 动态加载

```c
int engine_registry_load_plugin(engine_registry_t *registry,
                                 const char *plugin_path);
```

**功能**：
- 使用 `dlopen()` 加载 .so 文件
- 查找并调用 `engine_get_ops()` 函数
- 自动注册引擎到注册中心
- 维护插件句柄用于后续卸载

**示例**：
```c
engine_registry_load_plugin(registry, "./plugins/engine_dns.so");
```

### 2. 动态卸载

```c
int engine_registry_unload_plugin(engine_registry_t *registry,
                                   const char *name);
```

**功能**：
- 从注册中心注销引擎
- 调用引擎的 `cleanup()` 函数
- 使用 `dlclose()` 卸载动态库
- 释放插件句柄

**示例**：
```c
engine_registry_unload_plugin(registry, "DNS");
```

### 3. 热插拔

**特性**：
- 无需重启主程序
- 运行时添加/删除引擎
- 自动维护引擎优先级链
- 不影响其他引擎运行

## 插件开发

### 标准接口

每个插件必须实现：

```c
// 1. 引擎操作表
static const engine_ops_t my_engine_ops = {
    .name = "MyProtocol",
    .protocol_id = 1234,
    .priority = 100,
    .init = my_engine_init,
    .can_handle = my_engine_can_handle,
    .process = my_engine_process,
    .cleanup = my_engine_cleanup,
};

// 2. 导出函数
const engine_ops_t* engine_get_ops(void)
{
    return &my_engine_ops;
}
```

### 编译插件

```bash
# 单个插件
gcc -Wall -Wextra -O2 -fPIC -I.. -shared -o engine_dns.so engine_dns.c

# 使用 Makefile
cd plugins
make
```

### 编译选项说明

- `-fPIC`: 位置无关代码（动态库必需）
- `-shared`: 生成共享库
- `-I..`: 包含父目录头文件
- `-O2`: 优化级别

## 使用示例

### 完整流程

```c
#include "engine.h"

int main(void)
{
    // 1. 创建注册中心
    engine_registry_t *registry = engine_registry_create();
    
    // 2. 注册静态引擎
    engine_registry_register(registry, &http_engine_ops);
    
    // 3. 加载动态插件
    engine_registry_load_plugin(registry, "./plugins/engine_dns.so");
    engine_registry_load_plugin(registry, "./plugins/engine_quic.so");
    
    // 4. 处理数据包
    parsed_packet_t pkt;
    // ... 填充数据包 ...
    engine_process(registry, NULL, &pkt);
    
    // 5. 运行时卸载插件
    engine_registry_unload_plugin(registry, "DNS");
    
    // 6. 重新加载插件
    engine_registry_load_plugin(registry, "./plugins/engine_dns.so");
    
    // 7. 清理
    engine_registry_destroy(registry);
    
    return 0;
}
```

### 测试程序

```bash
# 编译所有
make all

# 运行基础测试
make test

# 运行插件测试
make test-plugin
```

## 文件结构

```
engine/
├── engine.h                    # 接口定义（含插件 API）
├── engine_registry.c           # 注册中心实现（含动态加载）
├── engine_http.c               # 静态 HTTP 引擎
├── test_plugin.c               # 插件测试程序
├── Makefile                    # 主构建文件
├── PLUGIN_SYSTEM.md            # 本文档
│
└── plugins/                    # 插件目录
    ├── README.md               # 插件开发指南
    ├── Makefile                # 插件构建文件
    ├── engine_template.c       # 开发模板
    ├── engine_dns.c            # DNS 插件示例
    └── engine_dns.so           # 编译后的插件
```

## 技术细节

### 插件管理

```c
typedef struct plugin_handle {
    void *dl_handle;                    // dlopen 返回的句柄
    char name[ENGINE_NAME_MAX_LEN];     // 引擎名称
    struct plugin_handle *next;         // 链表指针
} plugin_handle_t;
```

### 加载流程

1. `dlopen(plugin_path, RTLD_LAZY)` - 加载动态库
2. `dlsym(handle, "engine_get_ops")` - 获取导出函数
3. 调用 `engine_get_ops()` 获取操作表
4. `engine_registry_register()` 注册引擎
5. 保存插件句柄到全局链表

### 卸载流程

1. 从插件链表查找句柄
2. `engine_registry_unregister()` 注销引擎
3. `dlclose(handle)` 关闭动态库
4. 从链表移除并释放句柄

## 优势

### 1. 灵活性
- 运行时添加新协议支持
- 无需修改主程序代码
- 支持第三方插件

### 2. 可维护性
- 插件独立开发和测试
- 模块化设计，职责清晰
- 易于调试和更新

### 3. 性能
- 按需加载，减少内存占用
- 不影响其他引擎性能
- 支持优先级调度

### 4. 扩展性
- 标准化接口
- 完整的开发模板
- 详细的文档支持

## 最佳实践

### 1. 插件命名
- 文件名：`engine_<protocol>.c`
- 引擎名：大写协议名（如 "DNS", "HTTP"）
- 动态库：`engine_<protocol>.so`

### 2. 优先级分配
- 100-199: 基础协议
- 200-299: 加密协议
- 300-399: 应用层协议
- 400+: 自定义协议

### 3. 错误处理
- 验证所有输入参数
- 检查 dlopen/dlsym 返回值
- 提供清晰的错误信息

### 4. 内存管理
- 在 init() 中分配资源
- 在 cleanup() 中释放资源
- 避免内存泄漏

### 5. 线程安全
- 不使用全局变量（除 ops 表）
- 状态存储在 ctx->private_data
- 必要时使用互斥锁

## 测试结果

```
=== Engine Plugin System Test ===
✓ 静态引擎注册成功
✓ 动态插件加载成功
✓ 数据包处理正常
✓ 插件卸载成功
✓ 插件重新加载成功
✓ 资源清理完整

All Tests Complete
```

## 故障排查

### 常见问题

1. **插件加载失败**
   - 检查文件路径
   - 验证 .so 文件权限
   - 确认依赖库已安装

2. **符号未找到**
   - 确保导出了 `engine_get_ops()`
   - 检查函数签名是否正确
   - 使用 `nm -D` 查看导出符号

3. **运行时崩溃**
   - 检查指针有效性
   - 验证内存分配
   - 使用 gdb 调试

## 参考资料

- 主引擎文档: `README.md`
- 插件开发指南: `plugins/README.md`
- 接口定义: `engine.h`
- 示例代码: `plugins/engine_dns.c`
- 测试程序: `test_plugin.c`

## 总结

动态插件系统为引擎注册中心提供了强大的扩展能力，实现了真正的热插拔功能。通过标准化的接口和完善的工具链，开发者可以轻松创建和部署新的协议解析引擎。
