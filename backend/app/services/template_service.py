from ..schemas.chat import TemplateRequest, TemplateResponse


class TemplateService:
    def generate(self, req: TemplateRequest) -> TemplateResponse:
        name = req.protocol.strip() or "MyProtocol"
        symbol = "".join(ch.lower() if ch.isalnum() else "_" for ch in name).strip("_") or "myproto"
        template = f"""#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include "../engine.h"

static int {symbol}_init(engine_ctx_t *ctx)
{{
    ctx->private_data = NULL;
    return ENGINE_OK;
}}

static bool {symbol}_can_handle(engine_ctx_t *ctx,
                                const session_entry_t *session,
                                const parsed_packet_t *pkt)
{{
    (void)ctx;
    (void)session;
    if (!pkt || !pkt->payload || pkt->payload_len == 0) return false;

    /* TODO: 只做快速筛选，例如协议号、端口、payload 前几个字节。 */
    return false;
}}

static int {symbol}_process(engine_ctx_t *ctx,
                            session_entry_t *session,
                            const parsed_packet_t *pkt)
{{
    (void)ctx;
    (void)session;
    if (!pkt || !pkt->payload || pkt->payload_len == 0) return ENGINE_CONTINUE;

    /* TODO: 在这里解析 {name} 的关键特征。
     * 注意边界检查，不要在热路径做同步 I/O 或复杂阻塞操作。
     */

    return ENGINE_CONTINUE;
}}

static void {symbol}_cleanup(engine_ctx_t *ctx)
{{
    if (ctx) ctx->private_data = NULL;
}}

static const engine_ops_t {symbol}_ops = {{
    .name = "{name}",
    .protocol_id = 0,      /* TODO: 按课程要求填写 */
    .priority = 200,       /* TODO: 根据插件链顺序调整 */
    .init = {symbol}_init,
    .can_handle = {symbol}_can_handle,
    .process = {symbol}_process,
    .cleanup = {symbol}_cleanup,
    .get_stats = NULL,
    .reset_stats = NULL,
}};

const engine_ops_t* engine_get_ops(void)
{{
    return &{symbol}_ops;
}}
"""
        checklist = [
            "can_handle 只做轻量筛选，不做完整解析。",
            "process 读取 payload 前必须检查 payload_len。",
            "不在热路径做同步 I/O、长时间锁等待或大量 malloc。",
            "根据是否需要后续插件继续观察，谨慎选择 ENGINE_HANDLED 或 ENGINE_CONTINUE。",
            "优先阅读 plugin/plugins/engine_template.c 和一个相近协议插件。",
        ]
        explanation = (
            f"这是 {name} 的学习用插件骨架，保留了 TODO，目的是帮助学生理解 engine_ops_t "
            "接口和热路径约束，不是完整作业答案。"
        )
        return TemplateResponse(
            template=template,
            explanation=explanation,
            checklist=checklist,
            references=["plugin/engine.h", "plugin/plugins/engine_template.c", "plugin/README.md"],
        )

