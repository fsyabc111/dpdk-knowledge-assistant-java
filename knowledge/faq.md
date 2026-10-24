# 教学 FAQ

## can_handle 和 process 有什么区别？

`can_handle()` 用于快速判断当前包是否值得交给插件处理，应该只做轻量判断，例如协议号、端口、payload 前几个字节。`process()` 用于真正解析协议特征、更新会话状态或输出检测结果。

参考阅读：

- `plugin/README.md`
- `plugin/engine.h`
- `plugin/plugins/engine_template.c`

## 为什么不在热路径做同步 I/O？

协议插件运行在包处理热路径上。如果每个包都做同步 I/O、复杂分配或长时间锁等待，会直接影响抓包吞吐和检测稳定性。

## 学生插件开发应该先看哪些文件？

建议顺序：

1. `plugin/README.md`
2. `plugin/engine.h`
3. `plugin/plugins/engine_template.c`
4. 一个相近协议的现有插件，例如 `plugin/plugins/http_plugin.c`

