package io.dpdk.assistant.agent.fallback;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.tools.GradeFacts;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;

/**
 * 模型生成不可用时的确定性降级答案（源 services/agent_service.py:build_fallback_answer）。
 *
 * <p>grading_results 非空时（本轮已成功完成评测）必须保留评分事实：评测已经花掉
 * 配额与真实调用，降级答案丢掉得分/失败用例会让这次评测白做。按要求只给事实与
 * 自查方向，不给修复代码。既有文案「模型生成暂时不可用」「无法可靠回答这个问题」
 * 保持原样（外部契约）。
 */
public final class FallbackAnswer {

    /** 回显到降级答案里的提问预览上限（不回显完整学生源码/长文）。 */
    static final int QUESTION_PREVIEW_CHARS = 120;
    /** 预览里围栏代码块的占位形态（与落库口径的 [代码已省略] 区分，见 B8）。 */
    static final String QUESTION_ELIDED = "（含代码片段，已省略）";

    /** 降级原因说明。上下文超限不等于「上游故障」，必须如实区分。 */
    static final Map<String, String> REASON_NOTES = Map.of(
            "context_budget", "说明：本次请求的上下文超出服务上限，系统未继续调用模型。");

    private FallbackAnswer() {
    }

    /** 把用户提问整理成可安全回显的短预览（与落库路径同一 redact 消毒口径）。 */
    public static String sanitizeQuestion(String question) {
        String text = Redact.redact(question == null ? "" : question)
                .replace("[代码已省略]", QUESTION_ELIDED);
        // Python " ".join(text.split())：按任意空白折叠并去首尾。
        text = String.join(" ", text.trim().split("\\s+"));
        if (CodePoints.length(text) > QUESTION_PREVIEW_CHARS) {
            text = CodePoints.truncate(text, QUESTION_PREVIEW_CHARS) + "…";
        }
        return text;
    }

    /** 确定性降级答案；reason 仅支持 context_budget（其余按无原因处理）。 */
    public static String build(String question, List<KbChunk> chunks,
                               List<Map<String, Object>> gradingResults, String reason) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<String, Object> payload : gradingResults == null ? List.<Map<String, Object>>of()
                : gradingResults) {
            Map<String, Object> item = GradeFacts.facts(payload);
            if (item != null) {
                facts.add(item);
            }
        }
        if ((chunks == null || chunks.isEmpty()) && facts.isEmpty()) {
            return "直接结论：当前知识库还没有检索到足够依据，无法可靠回答这个问题。\n\n"
                    + "解释：MVP 会优先基于已索引的 Markdown 和代码回答；如果资料未覆盖，我不会编造。\n\n"
                    + "下一步：请先运行知识索引脚本，或补充 FAQ / 实验说明后重建索引。";
        }

        List<String> sections = new ArrayList<>();
        if (!facts.isEmpty()) {
            List<String> blocks = new ArrayList<>();
            for (Map<String, Object> item : facts) {
                String protocol = GradeFacts.str(item.get("protocol"));
                blocks.add("【" + (protocol.isEmpty() ? "插件" : protocol.toUpperCase()) + "】"
                        + String.join("\n", GradeFacts.factsLines(item)));
            }
            sections.add("直接结论：模型生成暂时不可用，但本轮评测已完成，以下是确定性的评测结果。");
            sections.add("评测结果：\n" + String.join("\n\n", blocks));
            sections.add("解释：以上为评测工具的原始判定，未经过模型改写，请对照失败用例的"
                    + "「期望 / 实际」差异自行定位实现问题。按课程规范，我不直接给出修复代码。");
        } else {
            String preview = sanitizeQuestion(question);
            sections.add("直接结论：模型生成暂时不可用，但已经检索到可参考资料。");
            sections.add(preview.isEmpty()
                    ? "解释：已经检索到与本次提问相关的项目资料。"
                    : "解释：问题“" + preview + "”与以下项目资料相关，可以先阅读这些来源。");
        }

        String note = REASON_NOTES.get(reason == null ? "" : reason);
        if (note != null) {
            sections.add(note);
        }

        if (chunks != null && !chunks.isEmpty()) {
            StringBuilder sources = new StringBuilder();
            int shown = 0;
            for (KbChunk chunk : chunks) {
                if (shown >= 3) {
                    break;
                }
                shown++;
                String section = !chunk.section().isEmpty() ? chunk.section() : chunk.title();
                sources.append("- ").append(chunk.sourcePath()).append("：").append(section).append("\n");
            }
            sections.add("参考来源：\n" + sources.substring(0, sources.length() - 1));
        }

        if (!facts.isEmpty()) {
            sections.add("下一步：先检查失败用例对应的字段解析与判定分支；若需要讲解具体协议"
                    + "字段，可稍后重试教学反馈。");
        } else {
            sections.add("下一步：请稍后重试；若问题持续出现，请联系管理员查看服务日志。");
        }
        return String.join("\n\n", sections);
    }
}
