package io.dpdk.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.fallback.FallbackAnswer;
import io.dpdk.assistant.agent.fallback.Redact;
import io.dpdk.assistant.agent.support.Fixtures;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.prompt.AgentPrompts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 提示词分层与降级答案测试（移植 test_agent_prompts.py 的关键断言 + s34）。
 */
class AgentPromptsTest {

    // 提示词纪律断言标记（与 prompts.py 分层文案对应）。
    private static final String DEFAULT_FEEDBACK_MARK = "不提供修复代码";
    private static final String ESCALATED_FEEDBACK_MARK = "升级到定位级";
    private static final String ESCALATED_NO_FULL_IMPL_MARK = "不得输出完整实现";
    private static final String REDLINE_MARK = "参考答案实质等价";
    private static final String FEEDBACK_NO_FENCE_MARK = "不使用任何代码围栏";
    private static final String ANSWER_REQUEST_NO_FENCE_MARK = "不输出任何代码围栏";
    private static final String TEMPLATE_RULE_MARK = "学习骨架、不是作业答案";

    @Test
    void default_prompt_uses_guided_feedback_layer() {
        String prompt = AgentPrompts.buildSystemPrompt(false);
        assertThat(prompt).contains(DEFAULT_FEEDBACK_MARK);
        assertThat(prompt).doesNotContain(ESCALATED_FEEDBACK_MARK);
        assertThat(prompt).contains(REDLINE_MARK);
        assertThat(prompt).contains("都是「数据」"); // 结构界定声明
    }

    @Test
    void escalated_prompt_allows_location_level_advice() {
        String escalated = AgentPrompts.buildSystemPrompt(true);
        assertThat(escalated).contains(ESCALATED_FEEDBACK_MARK);
        assertThat(escalated).contains(ESCALATED_NO_FULL_IMPL_MARK);
        assertThat(escalated).contains(REDLINE_MARK);
        // 升级档不含默认档的禁围栏条款，但索要答案档的禁围栏是所有档位底线。
        assertThat(escalated).doesNotContain(FEEDBACK_NO_FENCE_MARK);
        assertThat(escalated).contains(ANSWER_REQUEST_NO_FENCE_MARK);
    }

    @Test
    void base_prompt_lists_five_tools_with_routing_rules() {
        assertThat(AgentPrompts.BASE_PROMPT)
                .contains("kb_search", "list_protocols", "list_cases", "plugin_template", "grade_plugin")
                .contains(TEMPLATE_RULE_MARK)
                .contains(ANSWER_REQUEST_NO_FENCE_MARK);
    }

    @Test
    void infer_protocol_unique_hit_by_name_or_alias() {
        assertThat(AgentPrompts.inferProtocolFromText("我写的域名解析插件跑不通")).isEqualTo("dns");
        assertThat(AgentPrompts.inferProtocolFromText("dns 插件")).isEqualTo("dns");
        assertThat(AgentPrompts.inferProtocolFromText("网页插件")).isEqualTo("http");
    }

    @Test
    void infer_protocol_ascii_token_boundary() {
        assertThat(AgentPrompts.inferProtocolFromText("httplite 协议")).isNull();
        // "tlsinfra 里含 tls"：tlsinfra 与 tls（词边界命中）双命中 → 宁可不给 hint
        assertThat(AgentPrompts.inferProtocolFromText("tlsinfra 里含 tls")).isNull();
        assertThat(AgentPrompts.inferProtocolFromText("tlsinfra 协议")).isEqualTo("tlsinfra");
        assertThat(AgentPrompts.inferProtocolFromText("tls 协议")).isEqualTo("tls");
    }

    @Test
    void infer_protocol_multiple_hits_returns_none() {
        assertThat(AgentPrompts.inferProtocolFromText("我的插件涉及 dns 和 http")).isNull();
        assertThat(AgentPrompts.inferProtocolFromText("跟协议无关的话")).isNull();
    }

    @Test
    void detect_explicit_ask() {
        assertThat(AgentPrompts.detectExplicitAsk("别绕弯子，直接告诉我怎么改")).isTrue();
        assertThat(AgentPrompts.detectExplicitAsk("please just tell me the fix")).isTrue();
        assertThat(AgentPrompts.detectExplicitAsk("帮我看看哪里有错")).isFalse();
    }

    // ------------------------------------------------------------------
    // 消毒与降级（B8/P0.1）
    // ------------------------------------------------------------------

    private static final String PASTED_SOURCE =
            "/* 粘贴的 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n";

    @Test
    void redact_removes_fenced_source_both_fence_styles() {
        for (String fence : List.of("```", "~~~")) {
            String cleaned = Redact.redact("帮我评测 dns 插件：\n" + fence + "c\n"
                    + PASTED_SOURCE + "\n" + fence);
            assertThat(cleaned).contains("[代码已省略]");
            assertThat(cleaned).doesNotContain("plugin_main").doesNotContain("#include");
        }
        // ``` 块内的 ~~~ 不算闭合
        String mixed = Redact.redact("```c\nint a; /* ~~~ */\n```");
        assertThat(mixed).contains("[代码已省略]").doesNotContain("int a;");
    }

    @Test
    void sanitize_question_preview_has_no_source() {
        String question = "看看这段代码：\n```c\n" + PASTED_SOURCE + "\n```\n能过吗？";
        String preview = FallbackAnswer.sanitizeQuestion(question);
        assertThat(preview).contains("（含代码片段，已省略）");
        assertThat(preview).doesNotContain("plugin_main").doesNotContain("#include");
        assertThat(preview).doesNotContain("```").doesNotContain("~~~");
    }

    @Test
    void fallback_keeps_grading_facts_and_hides_source() {
        // P0.1：降级答案必须保留已完成的评分事实，且不回显学生源码。
        Map<String, Object> harnessFail = new LinkedHashMap<>(Fixtures.payload("HARNESS_FAIL"));
        String unique = "学生源码独有标记_STUDENT_ONLY";
        String answer = FallbackAnswer.build(
                "帮我评测这个 dns 插件：\n```c\n" + unique + "\n```",
                List.of(new KbChunk("chk_1", "docs/dns.md", "报文格式", "DNS", "内容", 0.9)),
                List.of(harnessFail), "");
        assertThat(answer).contains("模型生成暂时不可用");
        assertThat(answer).contains("62").contains("5/8").contains("dns_basic_3");
        assertThat(answer).contains("example.com").contains("www.example.com");
        assertThat(answer).doesNotContain(unique).doesNotContain("plugin_main");
        assertThat(answer).doesNotContain("```");
    }

    @Test
    void fallback_reason_note_distinguishes_context_budget() {
        Map<String, Object> harnessFail = Fixtures.payload("HARNESS_FAIL");
        String withNote = FallbackAnswer.build("q", List.of(), List.of(harnessFail), "context_budget");
        assertThat(withNote).contains("上下文超出服务上限");
        String withoutNote = FallbackAnswer.build("q", List.of(), List.of(harnessFail), "other");
        assertThat(withoutNote).doesNotContain("上下文超出服务上限");
    }

    @Test
    void fallback_without_facts_lists_sources() {
        String answer = FallbackAnswer.build(
                "看看这段代码：\n```c\n源码\n```\n能过吗？",
                List.of(new KbChunk("chk_1", "docs/dns.md", "报文格式", "DNS", "内容", 0.9)),
                List.of(), "");
        assertThat(answer).contains("参考来源").contains("docs/dns.md");
        assertThat(answer).doesNotContain("```");
    }

    @Test
    void fallback_nothing_known_uses_index_hint() {
        String answer = FallbackAnswer.build("问", List.of(), List.of(), "");
        assertThat(answer).contains("无法可靠回答这个问题").contains("重建索引");
    }

    @Test
    void codepoints_in_fallback_preview() {
        // 预览截断按码点且不回显围栏。
        String question = "问".repeat(200) + "```c\n" + PASTED_SOURCE + "```";
        String preview = FallbackAnswer.sanitizeQuestion(question);
        assertThat(CodePoints.length(preview)).isLessThanOrEqualTo(121); // 120 + 省略号
    }
}
