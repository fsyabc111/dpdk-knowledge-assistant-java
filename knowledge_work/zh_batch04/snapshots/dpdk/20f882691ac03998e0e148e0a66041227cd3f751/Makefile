# 流量监测系统 - 主 Makefile
# 版本: 1.0
# 日期: 2026-01-27

# 编译器配置
CC = gcc
CFLAGS = -Wall -Wextra -O2 -g -std=gnu99

# DPDK 链接选项
DPDK_PKGCONFIG := $(shell pkg-config --exists libdpdk 2>/dev/null && echo "yes")
ifeq ($(DPDK_PKGCONFIG),yes)
    DPDK_CFLAGS  := $(shell pkg-config --cflags libdpdk)
    DPDK_LDFLAGS := $(shell pkg-config --libs libdpdk)
else
    DPDK_CFLAGS  :=
    DPDK_LDFLAGS := -L/usr/local/lib/x86_64-linux-gnu -Wl,--as-needed -lrte_node -lrte_graph -lrte_pipeline -lrte_table -lrte_pdump -lrte_port -lrte_fib -lrte_ipsec -lrte_vhost -lrte_stack -lrte_security -lrte_sched -lrte_reorder -lrte_rib -lrte_dmadev -lrte_regexdev -lrte_rawdev -lrte_power -lrte_pcapng -lrte_member -lrte_lpm -lrte_latencystats -lrte_jobstats -lrte_ip_frag -lrte_gso -lrte_gro -lrte_gpudev -lrte_eventdev -lrte_efd -lrte_distributor -lrte_cryptodev -lrte_compressdev -lrte_cfgfile -lrte_bpf -lrte_bitratestats -lrte_bbdev -lrte_acl -lrte_timer -lrte_hash -lrte_metrics -lrte_cmdline -lrte_pci -lrte_ethdev -lrte_meter -lrte_net -lrte_mbuf -lrte_mempool -lrte_rcu -lrte_ring -lrte_eal -lrte_telemetry -lrte_kvargs
endif

# 启用 DPDK 后端编译支持（与 capture/Makefile 保持一致）
CFLAGS += -DCAPTURE_ENABLE_DPDK $(DPDK_CFLAGS)

# libcurl（ClickHouse HTTP 写入）
CURL_CFLAGS := $(shell pkg-config --cflags libcurl 2>/dev/null)
CURL_LIBS   := $(shell pkg-config --libs   libcurl 2>/dev/null)
ifeq ($(CURL_LIBS),)
    CURL_LIBS := -lcurl
endif

# MongoDB C Driver（证书存储）
MONGOC_CFLAGS := $(shell pkg-config --cflags libmongoc-1.0 2>/dev/null)
MONGOC_LIBS   := $(shell pkg-config --libs   libmongoc-1.0 2>/dev/null)
ifeq ($(MONGOC_LIBS),)
    MONGOC_CFLAGS := -I/usr/include/libmongoc-1.0 -I/usr/include/libbson-1.0
    MONGOC_LIBS   := -lmongoc-1.0 -lbson-1.0
endif

CFLAGS += $(CURL_CFLAGS) $(MONGOC_CFLAGS)

LDFLAGS = -lpthread -rdynamic -ldl -Wl,-export-dynamic \
          -Wl,-rpath,\$$ORIGIN/stats \
          $(DPDK_LDFLAGS) -lpcap \
          -lssl -lcrypto $(CURL_LIBS) $(MONGOC_LIBS) -lstdc++

# 目录配置
CAPTURE_DIR = capture
SESSION_DIR = session
PLUGIN_DIR = plugin
STATS_DIR = stats
LOGGER_DIR = logger

# 包含路径
INCLUDES = -I. -I$(CAPTURE_DIR) -I$(SESSION_DIR) -I$(PLUGIN_DIR) -I$(STATS_DIR) \
           -Iplugin/plugins -I$(LOGGER_DIR)

# 库文件
CAPTURE_LIB = $(CAPTURE_DIR)/libcapture.a
SESSION_LIB = $(SESSION_DIR)/libsession.a
PLUGIN_LIB = $(PLUGIN_DIR)/libengine.a
STATS_LIB = $(STATS_DIR)/libstats.so
LOGGER_LIB = $(LOGGER_DIR)/liblogger.a

LIBS = $(CAPTURE_LIB) $(SESSION_LIB) $(PLUGIN_LIB) $(STATS_LIB) $(LOGGER_LIB)

# 目标程序
TARGET = traffic_monitor
OBJS = main.o log.o http_server.o plugin/engine_log.o plugin/plugins/ch_storage.o plugin/plugins/mongo_storage.o plugin/plugins/ch_event.o

# 默认目标
.PHONY: all
all: $(TARGET)

# 编译主程序
$(TARGET): $(OBJS) $(LIBS)
	@echo "链接主程序: $@"
	$(CC) $(CFLAGS) -o $@ $(OBJS) $(LIBS) $(LDFLAGS)
	@echo "编译完成: $@"

main.o: main.c log.h http_server.h logger/logger_thread.h \
        logger/plugin_log.h plugin/plugins/ch_event.h stats/event_batch.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

log.o: log.c log.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

http_server.o: http_server.c http_server.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

plugin/engine_log.o: plugin/engine_log.c plugin/engine_log.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

plugin/plugins/ch_storage.o: plugin/plugins/ch_storage.c plugin/plugins/ch_storage.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

plugin/plugins/mongo_storage.o: plugin/plugins/mongo_storage.c plugin/plugins/mongo_storage.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

plugin/plugins/ch_event.o: plugin/plugins/ch_event.c plugin/plugins/ch_event.h
	@echo "编译: $<"
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

# 编译各模块
$(CAPTURE_LIB):
	@echo "编译抓包模块..."
	$(MAKE) -C $(CAPTURE_DIR)

$(SESSION_LIB):
	@echo "编译会话管理模块..."
	$(MAKE) -C $(SESSION_DIR)

$(PLUGIN_LIB):
	@echo "编译协议解析引擎..."
	$(MAKE) -C $(PLUGIN_DIR)

$(STATS_LIB):
	@echo "编译统计模块..."
	$(MAKE) -C $(STATS_DIR)

$(LOGGER_LIB):
	@echo "编译日志线程模块..."
	$(MAKE) -C $(LOGGER_DIR)

# ──────────────────────────────────────────────────────────────────────────────
# ASAN 构建（内存错误检测）
# 用法：make asan
# ──────────────────────────────────────────────────────────────────────────────
ASAN_FLAGS   = -fsanitize=address -fno-omit-frame-pointer -O1 -g
TARGET_ASAN  = traffic_monitor_asan

.PHONY: asan
asan:
	@echo "=== 编译 ASAN 版本（清理旧目标文件）==="
	$(MAKE) -C $(SESSION_DIR) clean
	$(MAKE) -C $(CAPTURE_DIR) clean
	$(MAKE) -C $(PLUGIN_DIR)  clean
	$(MAKE) -C plugin/plugins clean
	@echo "=== 重新编译所有模块（带 ASAN）==="
	$(MAKE) -C $(SESSION_DIR) EXTRA_CFLAGS="$(ASAN_FLAGS)"
	$(MAKE) -C $(CAPTURE_DIR) EXTRA_CFLAGS="$(ASAN_FLAGS)"
	$(MAKE) -C $(PLUGIN_DIR)  EXTRA_CFLAGS="$(ASAN_FLAGS)"
	$(MAKE) -C plugin/plugins EXTRA_CFLAGS="$(ASAN_FLAGS)"
	$(MAKE) -C $(STATS_DIR)
	@echo "=== 链接 ASAN 主程序 ==="
	$(CC) $(CFLAGS) $(ASAN_FLAGS) $(INCLUDES) -c main.c -o main_asan.o
	$(CC) $(CFLAGS) $(ASAN_FLAGS) $(INCLUDES) -c log.c  -o log_asan.o
	$(CC) $(CFLAGS) $(ASAN_FLAGS) $(INCLUDES) -c plugin/plugins/ch_storage.c    -o plugin/plugins/ch_storage_asan.o
	$(CC) $(CFLAGS) $(ASAN_FLAGS) $(INCLUDES) -c plugin/plugins/mongo_storage.c -o plugin/plugins/mongo_storage_asan.o
	$(CC) $(CFLAGS) $(ASAN_FLAGS) -o $(TARGET_ASAN) \
	    main_asan.o \
	    log_asan.o \
	    plugin/plugins/ch_storage_asan.o \
	    plugin/plugins/mongo_storage_asan.o \
	    $(CAPTURE_LIB) $(SESSION_LIB) $(PLUGIN_LIB) $(STATS_LIB) \
	    $(LDFLAGS) -fsanitize=address
	@echo ""
	@echo "=== ASAN 版本编译完成: $(TARGET_ASAN) ==="
	@echo "NOTE: 当前 .so 插件为 ASAN 版，运行 'make' 可恢复正常版"

# 清理
.PHONY: clean
clean:
	@echo "清理主程序..."
	rm -f $(OBJS) $(TARGET)
	@echo "清理各模块..."
	$(MAKE) -C $(CAPTURE_DIR) clean
	$(MAKE) -C $(SESSION_DIR) clean
	$(MAKE) -C $(PLUGIN_DIR) clean
	$(MAKE) -C $(STATS_DIR) clean
	$(MAKE) -C $(LOGGER_DIR) clean

# 深度清理
.PHONY: distclean
distclean: clean
	@echo "深度清理..."
	find . -name "*.o" -delete
	find . -name "*.a" -delete
	find . -name "*.so" -delete
	find . -name "*~" -delete

# 安装
.PHONY: install
install: $(TARGET)
	@echo "安装到 /usr/local/bin..."
	install -m 755 $(TARGET) /usr/local/bin/

# 卸载
.PHONY: uninstall
uninstall:
	@echo "卸载..."
	rm -f /usr/local/bin/$(TARGET)

# 测试
.PHONY: test
test: $(TARGET)
	@echo "运行测试..."
	./$(TARGET) --help

# 帮助
.PHONY: help
help:
	@echo "流量监测系统 - Makefile 使用说明"
	@echo ""
	@echo "可用目标:"
	@echo "  make          - 编译主程序和所有模块"
	@echo "  make clean    - 清理编译文件"
	@echo "  make distclean- 深度清理"
	@echo "  make install  - 安装到系统"
	@echo "  make uninstall- 从系统卸载"
	@echo "  make test     - 运行测试"
	@echo "  make help     - 显示此帮助"
	@echo ""
	@echo "示例:"
	@echo "  make                    # 编译"
	@echo "  sudo make install       # 安装"
	@echo "  ./traffic_monitor -h    # 查看帮助"
