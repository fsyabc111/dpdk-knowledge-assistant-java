# 协议学习笔记

## HTTP

HTTP 明文协议通常可以从请求方法、Host、URL、User-Agent 等字段中提取特征。项目中可参考 `plugin/plugins/http_plugin.c`。

## DNS

DNS 检测通常关注查询名、查询类型、响应记录和异常查询模式。项目中可参考 `plugin/plugins/engine_dns.cpp`。

## TLS

TLS 检测不能解密应用数据，但可以关注 ClientHello、SNI、ALPN、证书和 JA3 等可观察特征。

