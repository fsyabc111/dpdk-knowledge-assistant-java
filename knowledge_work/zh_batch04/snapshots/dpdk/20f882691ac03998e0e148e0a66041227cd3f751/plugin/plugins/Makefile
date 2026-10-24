# Makefile for Engine Plugins

CC = gcc
CXX = g++
CFLAGS = -Wall -Wextra -O2 -fPIC -I.. -pthread
CXXFLAGS = -Wall -Wextra -O2 -fPIC -I.. -I../../stats -std=c++17 -pthread
UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
LDFLAGS = -shared -Wl,-undefined,dynamic_lookup
else
LDFLAGS = -shared
endif

# libcurl（ClickHouse HTTP 写入）
CURL_CFLAGS := $(shell pkg-config --cflags libcurl 2>/dev/null)
CURL_LIBS   := $(shell pkg-config --libs   libcurl 2>/dev/null)
ifeq ($(CURL_LIBS),)
    CURL_LIBS := -lcurl
endif

# OpenSSL
OPENSSL_CFLAGS := $(shell pkg-config --cflags openssl 2>/dev/null)
OPENSSL_LIBS   := $(shell pkg-config --libs   openssl 2>/dev/null)
ifeq ($(OPENSSL_LIBS),)
    OPENSSL_LIBS := -lssl -lcrypto
endif

# MongoDB C Driver（证书存储）
MONGOC_CFLAGS := $(shell pkg-config --cflags libmongoc-1.0 2>/dev/null)
MONGOC_LIBS   := $(shell pkg-config --libs   libmongoc-1.0 2>/dev/null)
ifeq ($(MONGOC_LIBS),)
    MONGOC_CFLAGS := -I/usr/include/libmongoc-1.0 -I/usr/include/libbson-1.0
    MONGOC_LIBS   := -lmongoc-1.0 -lbson-1.0
endif

MAXMINDDB_CFLAGS := $(shell pkg-config --cflags libmaxminddb 2>/dev/null)
MAXMINDDB_LIBS   := $(shell pkg-config --libs   libmaxminddb 2>/dev/null)

DPDK_PKGCONFIG := $(shell pkg-config --exists libdpdk 2>/dev/null && echo "yes")
ifeq ($(DPDK_PKGCONFIG),yes)
    DPDK_CFLAGS := $(shell pkg-config --cflags libdpdk)
else
    DPDK_CFLAGS :=
endif

CFLAGS += $(OPENSSL_CFLAGS) $(CURL_CFLAGS) $(MONGOC_CFLAGS) -DCAPTURE_ENABLE_DPDK $(DPDK_CFLAGS)
CXXFLAGS += $(OPENSSL_CFLAGS) $(CURL_CFLAGS) $(MONGOC_CFLAGS) -DCAPTURE_ENABLE_DPDK $(DPDK_CFLAGS)
LIBS    = $(OPENSSL_LIBS) $(CURL_LIBS) $(MONGOC_LIBS)

# 默认目标：构建全部协议 .so。
# 必须在任何其他目标规则之前声明，否则 make 会把 ch_event.o 当默认目标，
# 导致 `make -C plugin/plugins` 只产出一个中间对象、所有 .so 一个都不建。
.DEFAULT_GOAL := all

# ch_event.c 编译为目标文件，由各需要它的插件链接。
# 注意：输出名必须和根 Makefile（traffic_monitor 主程序）用的 ch_event.o **不同**。
# 主程序那份不带 -fPIC（链进可执行文件时不需要），插件这份必须带 -fPIC（链进 .so）。
# 如果共用 ch_event.o，谁后生成谁覆盖，另一方会链接失败：
#   - .so 链接时报: relocation ... can not be used when making a shared object; recompile with -fPIC
#   - 主程序链接时若被覆盖反倒没问题，但会掩盖插件构建出错
# 专用文件名 ch_event_pic.o 把两个世界彻底隔开。
CH_EVENT_OBJ = ch_event_pic.o

$(CH_EVENT_OBJ): ch_event.c ch_event.h ch_storage.h
	$(CC) $(CFLAGS) -c ch_event.c -o $@

# 所有插件目标
PLUGINS = \
	engine_http.so \
	engine_geo_policy.so \
	engine_https.so \
	engine_tls.so \
	engine_cert.so \
	engine_ja3.so \
	engine_dns.so \
	engine_ftp.so \
	engine_smtp.so \
	engine_imap.so \
	engine_openvpn.so \
	engine_pop3.so \
	engine_quic.so \
	engine_rtsp.so \
	engine_wireguard.so \
	engine_ssh.so \
	engine_ipsecvpn.so \
	engine_dtls.so \
	engine_tls_infra.so \
	engine_smb.so \
	engine_rdp.so \
	engine_rtmp.so \
	engine_tunnel.so \
	engine_auxiliary.so \
	engine_ti.so \
	engine_ids.so \
	engine_focus_site.so \
	engine_site_ranking.so \
	engine_v2ray.so \
	engine_shadowsocks.so

.PHONY: all clean

all: $(PLUGINS)

# ---- 各插件编译规则 ----
# ch_insert_* / mongo_insert_* 符号由主程序通过 -Wl,-export-dynamic 导出，
# dlopen 加载插件时自动解析，故插件本身不链接 ch_storage/mongo_storage。

engine_http.so: http_plugin.c http_parser.c http_parser.h ch_storage.h ch_event.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ http_plugin.c http_parser.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built: $@"

engine_geo_policy.so: engine_geo_policy.c geoip_mmdb.c address_role_learner.c \
	address_role_learner.h geoip_mmdb.h ch_storage.h ch_event.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(MAXMINDDB_CFLAGS) $(LDFLAGS) -o $@ \
		engine_geo_policy.c geoip_mmdb.c address_role_learner.c $(CH_EVENT_OBJ) \
		$(LIBS) $(MAXMINDDB_LIBS) -lm
	@echo "Built: $@"

engine_https.so: https_plugin.c https_identifier.c https_identifier.h \
		tls_parser.c tls_parser.h ch_storage.h ch_event.h $(CH_EVENT_OBJ) \
		../../session/session.h
	$(CC) $(CFLAGS) -I../.. $(LDFLAGS) -o $@ \
		https_plugin.c https_identifier.c tls_parser.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built: $@"

engine_tls.so: tls_plugin.c tls_parser.c tls_parser.h domain_intel.c domain_intel.h ch_storage.h ch_event.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ tls_plugin.c tls_parser.c domain_intel.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built: $@"

engine_cert.so: cert_plugin.c cert_extractor.c cert_extractor.h mongo_storage.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ cert_plugin.c cert_extractor.c $(LIBS)
	@echo "Built: $@"

engine_ja3.so: ja3_plugin.c ja3_fingerprint.c tls_parser.c ja3_fingerprint.h tls_parser.h ch_storage.h ch_event.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ ja3_plugin.c ja3_fingerprint.c tls_parser.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built: $@"

engine_dns.so: engine_dns.cpp domain_intel.c domain_intel.h ch_storage.h ch_event.h $(CH_EVENT_OBJ)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ engine_dns.cpp domain_intel.c $(CH_EVENT_OBJ) $(LIBS) -lstdc++
	@echo "Built C++ plugin: $@"

# 翻墙检测引擎: 依赖 JA3 指纹库 + TLS 解析器 + 数学库(-lm)
engine_tunnel.so: engine_tunnel.c ja3_fingerprint.c tls_parser.c \
		cross_border_types.h ja3_fingerprint.h tls_parser.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_tunnel.c ja3_fingerprint.c tls_parser.c $(OPENSSL_LIBS) -lm
	@echo "Built: $@"

# 辅助检测引擎: 威胁情报 + 隐蔽通道
engine_auxiliary.so: engine_auxiliary.c cross_border_types.h ch_event.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_auxiliary.c $(CH_EVENT_OBJ)
	@echo "Built: $@"

engine_v2ray.so: engine_v2ray.c ch_storage.h ../../session/session.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_v2ray.c $(LIBS) -lm
	@echo "Built: $@"

# Shadowsocks 概率型识别引擎（依赖 -lm 用于 log2/方差计算）
engine_shadowsocks.so: engine_shadowsocks.c ch_storage.h ch_event.h \
		../../session/session.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_shadowsocks.c $(LIBS) -lm
	@echo "Built: $@"

# ---- F4.2 新增插件 ----

# F4.2.1 威胁情报关联引擎
# Phase 4 重构：新增 ti_mmap_reader.c 作为主数据源（OpenCTI 链），保留 ti_intel_manager.c 做灰度降级
# 两者均为纯 C，必须用 gcc 编译
engine_ti.so: engine_ti.cpp \
		../../stats/ti_intel_manager.c ../../stats/ti_intel_manager.h \
		../../stats/ti_mmap_reader.c  ../../stats/ti_mmap_reader.h \
		../../stats/ti_mmap_format.h http_parser.c http_parser.h \
		ch_storage.h ch_event.h $(CH_EVENT_OBJ) cross_border_types.h dns_ext.h
	$(CC) $(CFLAGS) -c ../../stats/ti_intel_manager.c -o ti_intel_manager.o $(CURL_LIBS)
	$(CC) $(CFLAGS) -c ../../stats/ti_mmap_reader.c  -o ti_mmap_reader.o
	$(CC) $(CFLAGS) -c http_parser.c -o ti_http_parser.o
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ engine_ti.cpp ti_intel_manager.o ti_mmap_reader.o ti_http_parser.o $(CH_EVENT_OBJ) \
		-lstdc++ -lcurl -lpthread
	@echo "Built C++ plugin: $@"
	rm -f ti_intel_manager.o ti_mmap_reader.o ti_http_parser.o

# IDS 规则引擎（ET Open 脚手架）——手动构建，暂不加入 PLUGINS 默认列表
# 需要先 `python services/ids_rules/fetch_etopen.py --out /var/lib/netinsight/ids/rules.json`
# 再在主程序加载列表里手动加入 engine_ids.so
engine_ids.so: engine_ids.cpp http_parser.c http_parser.h ch_event.h ch_storage.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) -c http_parser.c -o ids_http_parser.o
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ engine_ids.cpp ids_http_parser.o $(CH_EVENT_OBJ) -lstdc++
	@echo "Built C++ plugin: $@"
	rm -f ids_http_parser.o

# F4.2.2 敏感站点热度监测引擎（独立实现，不依赖 domain_intel）
engine_focus_site.so: engine_focus_site.cpp \
		ch_storage.h ch_event.h $(CH_EVENT_OBJ) cross_border_types.h dns_ext.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ engine_focus_site.cpp $(CH_EVENT_OBJ) \
		-lstdc++ -lcurl
	@echo "Built C++ plugin: $@"

# F4.2.3 站点访问排名引擎（tls_parser.c 必须用 gcc）
engine_site_ranking.so: engine_site_ranking.cpp tls_parser.c tls_parser.h \
		ch_storage.h ch_event.h $(CH_EVENT_OBJ) cross_border_types.h
	$(CC) $(CFLAGS) -c tls_parser.c -o tls_parser.o
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ engine_site_ranking.cpp tls_parser.o $(CH_EVENT_OBJ) \
		-lstdc++ -lcurl
	@echo "Built C++ plugin: $@"
	rm -f tls_parser.o

# 通用规则必须把 $(CH_EVENT_OBJ) 声明为前置依赖，否则 make -j 并行时
# 可能先链接 .so 再编 ch_event_pic.o，报 "cannot find ch_event.o/ch_event_pic.o"。
# 受影响的典型插件：engine_pop3/engine_imap/engine_rtsp/engine_ipsecvpn。
%.so: %.cpp $(CH_EVENT_OBJ)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $< $(CH_EVENT_OBJ) -lstdc++
	@echo "Built C++ plugin: $@"

engine_ftp.so: engine_ftp.c ftp_pasv_registry.c ftp_pasv_registry.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_ftp.c ftp_pasv_registry.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built C plugin: $@"

engine_quic.so: engine_quic.c tls_parser.c tls_parser.h $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ engine_quic.c tls_parser.c $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built C plugin: $@"

%.so: %.c $(CH_EVENT_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $< $(CH_EVENT_OBJ) $(LIBS)
	@echo "Built C plugin: $@"

clean:
	rm -f $(PLUGINS) ch_storage.o mongo_storage.o $(CH_EVENT_OBJ) ch_event.o
	@echo "Clean complete"
