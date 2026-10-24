# 统计与存储服务模块

## 概述

统计与存储服务模块负责收集、聚合和持久化网络流量统计数据到 MongoDB 数据库。采用异步批量写入架构，确保高性能和低延迟。

## 核心特性

- **三层统计架构**：系统级、协议级、会话级统计
- **异步存储**：环形缓冲区 + 独立存储线程
- **批量写入**：自动批量提交，提高数据库性能
- **MongoDB 集成**：使用 libmongoc 驱动
- **线程安全**：多线程环境下安全使用

## 架构设计

```
应用层 → stats_update_*() → 环形缓冲区 → 存储线程 → MongoDB
                              (10000条)    (批量100条)
```

### 数据流程

1. 应用调用 `stats_update_*()` 提交统计数据
2. 数据写入无锁环形缓冲区（容量 10000）
3. 后台存储线程批量读取数据（每批 100 条）
4. 转换为 BSON 格式并批量写入 MongoDB
5. 定时刷新（1 秒）确保数据及时持久化

## MongoDB 集合结构

### system_stats - 系统统计
```javascript
{
  timestamp: NumberLong,      // 时间戳（毫秒）
  total_packets: NumberLong,  // 总包数
  total_bytes: NumberLong,    // 总字节数
  dropped_packets: NumberLong,// 丢包数
  active_sessions: NumberInt, // 活跃会话数
  cpu_usage: Double,          // CPU 使用率
  memory_usage_mb: Double     // 内存使用（MB）
}
```

### protocol_stats - 协议统计
```javascript
{
  timestamp: NumberLong,      // 时间戳
  protocol: String,           // 协议名称
  packet_count: NumberLong,   // 包数量
  byte_count: NumberLong,     // 字节数
  session_count: NumberInt,   // 会话数
  avg_packet_size: Double     // 平均包大小
}
```

### session_records - 会话记录
```javascript
{
  session_id: String,         // 会话ID
  protocol: String,           // 协议
  src_ip: String,             // 源IP
  src_port: NumberInt,        // 源端口
  dst_ip: String,             // 目标IP
  dst_port: NumberInt,        // 目标端口
  start_time: NumberLong,     // 开始时间
  end_time: NumberLong,       // 结束时间
  packets: NumberLong,        // 包数量
  bytes: NumberLong           // 字节数
}
```

## 编译

### 依赖项

```bash
# Ubuntu/Debian
sudo apt-get install libmongoc-dev libbson-dev

# CentOS/RHEL
sudo yum install mongo-c-driver-devel
```

### 编译库和测试

```bash
cd stats
make              # 编译 libstats.so
make test         # 编译并运行测试
```

## 使用示例

```c
#include "stats.h"

// 1. 创建统计管理器
stats_manager_t *manager = stats_manager_create(
    "mongodb://localhost:27017",
    "packet_analyzer"
);

// 2. 更新系统统计
system_stats_t sys_stats = {
    .timestamp = get_timestamp_ms(),
    .total_packets = 1000,
    .total_bytes = 1500000,
    .dropped_packets = 5,
    .active_sessions = 50,
    .cpu_usage = 25.5,
    .memory_usage_mb = 512.0
};
stats_update_system(manager, &sys_stats);

// 3. 更新协议统计
protocol_stats_t proto_stats = {
    .timestamp = get_timestamp_ms(),
    .protocol_name = "HTTP",
    .packet_count = 500,
    .byte_count = 750000,
    .session_count = 20,
    .avg_packet_size = 1500.0
};
stats_update_protocol(manager, &proto_stats);

// 4. 记录会话
session_record_t session = {
    .session_id = "sess_001",
    .protocol = "HTTP",
    .src_ip = "192.168.1.100",
    .src_port = 50000,
    .dst_ip = "10.0.0.1",
    .dst_port = 80,
    .start_time = start_ms,
    .end_time = end_ms,
    .packets = 100,
    .bytes = 150000
};
stats_record_session(manager, &session);

// 5. 获取当前系统统计
system_stats_t current;
stats_get_current_system(manager, &current);

// 6. 清理
stats_manager_destroy(manager);
```

## 性能优化

### 环形缓冲区
- 容量：10000 条记录
- 无锁设计（使用互斥锁保护临界区）
- 满时返回错误，应用层需处理

### 批量写入
- 批量大小：100 条/批
- 自动刷新：1 秒间隔
- 按类型分组批量插入

### 异步存储
- 独立存储线程
- 非阻塞提交
- 应用层零等待

## 监控与查询

### 查看统计数据

```bash
# 连接 MongoDB
mongosh

# 切换数据库
use packet_analyzer

# 查询最新系统统计
db.system_stats.find().sort({timestamp: -1}).limit(10)

# 查询特定协议统计
db.protocol_stats.find({protocol: "HTTP"}).limit(10)

# 查询会话记录
db.session_records.find().limit(10)

# 统计总记录数
db.system_stats.countDocuments()
db.protocol_stats.countDocuments()
db.session_records.countDocuments()
```

### 创建索引（可选）

```javascript
// 提高查询性能
db.system_stats.createIndex({timestamp: -1})
db.protocol_stats.createIndex({protocol: 1, timestamp: -1})
db.session_records.createIndex({session_id: 1})
db.session_records.createIndex({start_time: -1})
```

## 配置参数

在 `stats.c` 中可调整：

```c
#define RING_BUFFER_SIZE 10000    // 环形缓冲区大小
#define BATCH_SIZE 100            // 批量写入大小
#define FLUSH_INTERVAL_MS 1000    // 刷新间隔（毫秒）
```

## 故障排查

### MongoDB 连接失败
```bash
# 检查 MongoDB 服务状态
sudo systemctl status mongod

# 启动 MongoDB
sudo systemctl start mongod

# 测试连接
mongosh --eval "db.version()"
```

### 编译错误
```bash
# 检查 pkg-config
pkg-config --cflags --libs libmongoc-1.0

# 如果找不到，安装开发包
sudo apt-get install libmongoc-dev libbson-dev
```

### 缓冲区满
- 增加 `RING_BUFFER_SIZE`
- 减少 `FLUSH_INTERVAL_MS`
- 增加 `BATCH_SIZE`

## 集成到主程序

```c
// 在主程序初始化时
stats_manager_t *stats = stats_manager_create(
    "mongodb://localhost:27017",
    "packet_analyzer"
);

// 在抓包回调中更新统计
void packet_callback(packet_t *pkt) {
    // ... 处理数据包 ...
    
    // 更新系统统计
    system_stats_t sys;
    // ... 填充统计数据 ...
    stats_update_system(stats, &sys);
}

// 在会话结束时记录
void session_end_callback(session_t *sess) {
    session_record_t record;
    // ... 填充会话数据 ...
    stats_record_session(stats, &record);
}

// 程序退出时清理
stats_manager_destroy(stats);
```

## API 参考

### stats_manager_create
创建统计管理器
```c
stats_manager_t* stats_manager_create(
    const char *mongodb_uri,  // MongoDB 连接字符串
    const char *db_name       // 数据库名称
);
```

### stats_manager_destroy
销毁统计管理器
```c
void stats_manager_destroy(stats_manager_t *manager);
```

### stats_update_system
更新系统统计
```c
int stats_update_system(
    stats_manager_t *manager,
    const system_stats_t *stats
);
```

### stats_update_protocol
更新协议统计
```c
int stats_update_protocol(
    stats_manager_t *manager,
    const protocol_stats_t *stats
);
```

### stats_record_session
记录会话
```c
int stats_record_session(
    stats_manager_t *manager,
    const session_record_t *record
);
```

### stats_get_current_system
获取当前系统统计
```c
int stats_get_current_system(
    stats_manager_t *manager,
    system_stats_t *stats
);
```

## 许可证

与主项目相同
