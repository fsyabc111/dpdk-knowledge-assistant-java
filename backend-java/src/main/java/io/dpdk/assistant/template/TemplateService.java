package io.dpdk.assistant.template;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 插件模板生成（对齐 backend/app/services/template_service.py）。
 *
 * 纯函数：name = protocol.strip() 或 "MyProtocol"；symbol = 名称中字母/数字
 * （Unicode 口径）小写、其余替换为 "_"、去首尾 "_"，空则 "myproto"。
 * template_type/difficulty/requirements 仅参与契约校验，不改变输出（与源一致）。
 */
@Service
public final class TemplateService {

    public record TemplateResult(String template, String explanation, List<String> checklist,
                                 List<String> references) {
    }

    private TemplateService() {
    }

    public static TemplateResult generate(String protocol) {
        String name = protocol.strip().isEmpty() ? "MyProtocol" : protocol.strip();
        String symbol = symbolOf(name);
        String template = TEMPLATE.formatted(symbol, symbol, symbol, name, symbol, symbol,
                name, symbol, symbol, symbol, symbol, symbol);
        List<String> checklist = List.of(
                "can_handle 只做轻量筛选，不做完整解析。",
                "process 读取 payload 前必须检查 payload_len。",
                "不在热路径做同步 I/O、长时间锁等待或大量 malloc。",
                "根据是否需要后续插件继续观察，谨慎选择 ENGINE_HANDLED 或 ENGINE_CONTINUE。",
                "优先阅读 plugin/plugins/engine_template.c 和一个相近协议插件。");
        String explanation = "这是 " + name + " 的学习用插件骨架，保留了 TODO，目的是帮助学生理解 "
                + "engine_ops_t 接口和热路径约束，不是完整作业答案。";
        return new TemplateResult(template, explanation, checklist,
                List.of("plugin/engine.h", "plugin/plugins/engine_template.c", "plugin/README.md"));
    }

    /** "".join(ch.lower() if ch.isalnum() else "_" for ch in name).strip("_") or "myproto"。 */
    static String symbolOf(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toLowerCase(cp));
            } else {
                sb.append('_');
            }
        });
        String stripped = sb.toString();
        int start = 0;
        int end = stripped.length();
        while (start < end && stripped.charAt(start) == '_') {
            start++;
        }
        while (end > start && stripped.charAt(end - 1) == '_') {
            end--;
        }
        String out = stripped.substring(start, end);
        return out.isEmpty() ? "myproto" : out;
    }

    /**
     * 与 Python f-string 逐字节一致（冻结源 template_service.py；续行缩进：
     * can_handle 32 空格、process 28 空格、块内 4/5 空格）。占位序：
     * init/can_handle/process 符号、process 注释名称、cleanup 符号、ops 符号、
     * .name 名称、init/can_handle/process/cleanup 引用、engine_get_ops 符号。
     */
    private static final String TEMPLATE = String.join("\n",
            "#include <stdbool.h>",
            "#include <stdint.h>",
            "#include <stdlib.h>",
            "#include \"../engine.h\"",
            "",
            "static int %s_init(engine_ctx_t *ctx)",
            "{",
            "    ctx->private_data = NULL;",
            "    return ENGINE_OK;",
            "}",
            "",
            "static bool %s_can_handle(engine_ctx_t *ctx,",
            " ".repeat(32) + "const session_entry_t *session,",
            " ".repeat(32) + "const parsed_packet_t *pkt)",
            "{",
            "    (void)ctx;",
            "    (void)session;",
            "    if (!pkt || !pkt->payload || pkt->payload_len == 0) return false;",
            "",
            "    /* TODO: 只做快速筛选，例如协议号、端口、payload 前几个字节。 */",
            "    return false;",
            "}",
            "",
            "static int %s_process(engine_ctx_t *ctx,",
            " ".repeat(28) + "session_entry_t *session,",
            " ".repeat(28) + "const parsed_packet_t *pkt)",
            "{",
            "    (void)ctx;",
            "    (void)session;",
            "    if (!pkt || !pkt->payload || pkt->payload_len == 0) return ENGINE_CONTINUE;",
            "",
            "    /* TODO: 在这里解析 %s 的关键特征。",
            "     * 注意边界检查，不要在热路径做同步 I/O 或复杂阻塞操作。",
            "     */",
            "",
            "    return ENGINE_CONTINUE;",
            "}",
            "",
            "static void %s_cleanup(engine_ctx_t *ctx)",
            "{",
            "    if (ctx) ctx->private_data = NULL;",
            "}",
            "",
            "static const engine_ops_t %s_ops = {",
            "    .name = \"%s\",",
            "    .protocol_id = 0,      /* TODO: 按课程要求填写 */",
            "    .priority = 200,       /* TODO: 根据插件链顺序调整 */",
            "    .init = %s_init,",
            "    .can_handle = %s_can_handle,",
            "    .process = %s_process,",
            "    .cleanup = %s_cleanup,",
            "    .get_stats = NULL,",
            "    .reset_stats = NULL,",
            "};",
            "",
            "const engine_ops_t* engine_get_ops(void)",
            "{",
            "    return &%s_ops;",
            "}",
            "");
}
