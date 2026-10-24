/**
 * 引擎开发模板
 * 
 * 使用说明：
 * 1. 复制此文件并重命名为 engine_<protocol>.c
 * 2. 修改所有 "template" 为你的协议名称
 * 3. 实现各个函数
 * 4. 在 plugins/Makefile 中添加你的插件
 * 5. 编译: make -C plugins
 * 6. 测试: 使用 engine_registry_load_plugin() 加载
 */

#include "../engine.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "../../log.h"

/* ========== 引擎私有数据 ========== */

typedef struct template_engine_data {
    uint64_t packet_count;
    // TODO: 添加你的私有字段
} template_engine_data_t;

/* ========== 协议检测辅助函数 ========== */

static bool is_template_protocol(const uint8_t *payload, uint32_t len)
{
    // TODO: 实现协议检测逻辑
    // 例如：检查魔数、特征字节、协议头等
    
    if (len < 4) {
        return false;
    }
    
    // 示例：检查前4个字节是否匹配
    // return memcmp(payload, "MAGIC", 5) == 0;
    
    return false;
}

/* ========== 引擎接口实现 ========== */

/**
 * 初始化引擎
 * 分配私有数据，初始化状态
 */
static int template_engine_init(engine_ctx_t *ctx)
{
    template_engine_data_t *data = calloc(1, sizeof(template_engine_data_t));
    if (!data) {
        return ENGINE_ERROR;
    }
    
    ctx->private_data = data;
    
    // TODO: 添加初始化逻辑
    
    TM_LOG_INFO("Template Engine", "Initialized");
    return ENGINE_OK;
}

/**
 * 检查是否能处理该数据包
 * 快速判断，避免复杂计算
 */
static bool template_engine_can_handle(engine_ctx_t *ctx,
                                        const session_entry_t *session,
                                        const parsed_packet_t *pkt)
{
    (void)ctx;     // 未使用的参数
    (void)session; // 未使用的参数
    
    // TODO: 实现检测逻辑
    
    // 1. 检查传输层协议
    if (pkt->protocol != 6) {  // TCP=6, UDP=17
        return false;
    }
    
    // 2. 检查端口号
    if (pkt->dst_port != 1234 && pkt->src_port != 1234) {
        return false;
    }
    
    // 3. 检查 payload
    if (!pkt->payload || pkt->payload_len < 4) {
        return false;
    }
    
    // 4. 检查协议特征
    return is_template_protocol(pkt->payload, pkt->payload_len);
}

/**
 * 处理数据包
 * 解析协议，提取特征信息
 */
static int template_engine_process(engine_ctx_t *ctx,
                                    session_entry_t *session,
                                    const parsed_packet_t *pkt)
{
    template_engine_data_t *data = (template_engine_data_t *)ctx->private_data;
    
    (void)session; // 如果不需要会话信息
    
    // TODO: 实现协议解析逻辑
    
    // 示例：解析协议头
    // const protocol_header_t *hdr = (const protocol_header_t *)pkt->payload;
    
    // 示例：提取字段
    // uint32_t field1 = ntohl(hdr->field1);
    
    // 示例：打印信息
    TM_LOG_INFO("Template Engine", "Processed packet: len=%u", pkt->payload_len);
    
    // 更新统计
    data->packet_count++;
    
    // 返回值：
    // ENGINE_HANDLED - 已处理，停止后续引擎
    // ENGINE_CONTINUE - 继续下一个引擎
    // ENGINE_ERROR - 处理错误
    return ENGINE_HANDLED;
}

/**
 * 清理引擎资源
 * 释放私有数据，打印统计信息
 */
static void template_engine_cleanup(engine_ctx_t *ctx)
{
    if (ctx->private_data) {
        template_engine_data_t *data = (template_engine_data_t *)ctx->private_data;
        
        // TODO: 清理资源
        
        TM_LOG_INFO("Template Engine", "Cleanup: Processed %lu packets",
               data->packet_count);
        
        free(ctx->private_data);
        ctx->private_data = NULL;
    }
}

/**
 * 获取统计信息（可选）
 */
static int template_engine_get_stats(engine_ctx_t *ctx, engine_stats_t *stats)
{
    if (!ctx->private_data) {
        return ENGINE_ERROR;
    }
    
    // 复制基础统计
    *stats = ctx->stats;
    
    // TODO: 添加自定义统计
    
    return ENGINE_OK;
}

/* ========== 引擎操作表 ========== */

static const engine_ops_t template_engine_ops = {
    .name = "Template",           // TODO: 修改为你的协议名称
    .protocol_id = 1234,          // TODO: 修改为你的协议ID
    .priority = 100,              // TODO: 设置优先级 (越小越先执行)
    .init = template_engine_init,
    .can_handle = template_engine_can_handle,
    .process = template_engine_process,
    .cleanup = template_engine_cleanup,
    .get_stats = template_engine_get_stats,
    .reset_stats = NULL           // 可选
};

/* ========== 插件导出函数 ========== */

/**
 * 插件必须导出此函数
 * 返回引擎操作表指针
 */
const engine_ops_t* engine_get_ops(void)
{
    return &template_engine_ops;
}
