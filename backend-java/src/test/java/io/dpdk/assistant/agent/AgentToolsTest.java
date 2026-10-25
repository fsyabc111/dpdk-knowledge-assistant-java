package io.dpdk.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.port.QuotaExceededException;
import io.dpdk.assistant.agent.support.AgentScenario;
import io.dpdk.assistant.agent.support.FakeGradingPorts;
import io.dpdk.assistant.agent.support.FakeHarness;
import io.dpdk.assistant.agent.support.FakeLedger;
import io.dpdk.assistant.agent.support.FakeRetriever;
import io.dpdk.assistant.agent.support.Fixtures;
import io.dpdk.assistant.agent.tools.AgentTool;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.agent.tools.AgentTools;
import io.dpdk.assistant.agent.tools.FencedSource;
import io.dpdk.assistant.agent.tools.RetryableTaskFailure;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.retrieval.PythonJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 五工具与执行源身份测试（移植 test_agent_tools.py + test_s3b_source_identity.py）。
 *
 * <p>B6：执行源以服务端寄存为准（上传原件 > 粘贴块 > 恢复寄存源 > 模型参数复述），
 * MCP 入参与落库 code_hash 恒等于实际执行字节；B7：同 (protocol, 执行源) 的重复
 * 评测命中请求级去重表——先于配额扣减，不重打 MCP、不重复记账、不落第二条成绩。
 */
class AgentToolsTest {

    private static final String UPLOADED_SOURCE =
            "/* 上传原件 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 1;}\n";
    private static final String PASTED_SOURCE =
            "/* 粘贴的 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n";
    // 模型在 tool args 里复述的源码：与上传原件/粘贴块字节不同。
    private static final String MODEL_ARG_SOURCE =
            "/* 模型复述源码（字节不同） */\nint plugin_main(void){return -1;}\n";

    private static Map<String, Object> invoke(AgentTool tool, String protocol, String source) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", protocol);
        args.put("plugin_source", source);
        try {
            return tool.invoke(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> result) {
        return (Map<String, Object>) result.get("data");
    }

    private static String sha256(String text) {
        return AgentTools.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 工具面
    // ------------------------------------------------------------------

    @Test
    void tool_surface_is_exactly_five_tools() {
        AgentScenario scenario = AgentScenario.create();
        AgentToolContext context = scenario.buildContext();
        assertThat(AgentScenario.toolNames(context)).containsExactly(
                "kb_search", "list_protocols", "list_cases", "plugin_template", "grade_plugin");
    }

    // ------------------------------------------------------------------
    // kb_search
    // ------------------------------------------------------------------

    @Test
    void kb_search_returns_numbered_chunks_and_collects() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        AgentToolContext context = scenario.buildContext();
        AgentTool kb = AgentScenario.toolsByName(context).get("kb_search");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "qname 编码");
        Map<String, Object> result = kb.invoke(args);
        assertThat(result.get("ok")).isEqualTo(true);
        Map<String, Object> data = data(result);
        assertThat(data.get("count")).isEqualTo(1);
        assertThat(context.searchAttempted).isTrue();
        assertThat(context.collectedChunks).hasSize(1);
        assertThat(scenario.retriever.queries).containsExactly("qname 编码");
        assertThat(scenario.retriever.contexts.get(0)).isNull();
    }

    @Test
    void kb_search_collects_each_chunk_once_and_keeps_refs_positional() throws Exception {
        List<KbChunk> chunks = List.of(
                new KbChunk("chk_1", "docs/dns.md", "报文格式", "DNS", "qname 编码", 0.9),
                new KbChunk("chk_2", "docs/dns.md", "示例", "DNS2", "标签序列", 0.8));
        AgentScenario scenario = AgentScenario.create();
        scenario.retriever = new FakeRetriever(chunks);
        AgentToolContext context = scenario.buildContext();
        AgentTool kb = AgentScenario.toolsByName(context).get("kb_search");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "q");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = data(kb.invoke(args));
        assertThat(refs(first)).containsExactly(1, 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = data(kb.invoke(args));
        // 重复召回同一片段：已入账的复用原编号，不重复收集
        assertThat(refs(second)).containsExactly(1, 2);
        assertThat(context.collectedChunks).hasSize(2);
    }

    private static List<Integer> refs(Map<String, Object> data) {
        List<Integer> out = new ArrayList<>();
        for (Object item : (List<?>) data.get("chunks")) {
            out.add((Integer) ((Map<?, ?>) item).get("ref"));
        }
        return out;
    }

    @Test
    void kb_search_truncates_chunk_content_and_retrieval_failure_is_structured() throws Exception {
        // 单片段 1500 码点上限
        String longContent = "长".repeat(3000);
        AgentScenario scenario = AgentScenario.create();
        scenario.retriever = new FakeRetriever(List.of(
                new KbChunk("chk_big", "docs/big.md", "", "", longContent, 0.9)));
        AgentToolContext context = scenario.buildContext();
        AgentTool kb = AgentScenario.toolsByName(context).get("kb_search");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "q");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = data(kb.invoke(args));
        assertThat(CodePoints.length((String) ((Map<?, ?>) ((List<?>) data.get("chunks"))
                .get(0)).get("content"))).isEqualTo(1500);

        // 检索失败 → 结构化错误
        scenario.retriever = FakeRetriever.failing(new IllegalStateException("db down"));
        AgentToolContext context2 = scenario.buildContext();
        AgentTool kb2 = AgentScenario.toolsByName(context2).get("kb_search");
        Map<String, Object> failure = kb2.invoke(args);
        assertThat(failure.get("ok")).isEqualTo(false);
        assertThat(failure.get("error")).isEqualTo("retrieval_failed");
    }

    @Test
    void kb_search_missing_query_is_tool_exception_shape() {
        AgentScenario scenario = AgentScenario.create();
        AgentToolContext context = scenario.buildContext();
        AgentTool kb = AgentScenario.toolsByName(context).get("kb_search");
        // 缺必填参数：Python 签名 TypeError → run_tools 的 tool_exception；这里同样抛。
        assertThatThrownBy(() -> kb.invoke(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // list_protocols / list_cases / plugin_template
    // ------------------------------------------------------------------

    @Test
    void list_protocols_passthrough() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.harness.payloads.put("list_protocols", Fixtures.payload("LIST_PROTOCOLS_OK"));
        AgentToolContext context = scenario.buildContext();
        AgentTool tool = AgentScenario.toolsByName(context).get("list_protocols");
        Map<String, Object> result = tool.invoke(Map.of());
        assertThat(result.get("ok")).isEqualTo(true);
        Map<String, Object> data = data(result);
        assertThat(data.get("count")).isEqualTo(2);
        assertThat(scenario.harness.callsOf("list_protocols").get(0).get("args"))
                .isEqualTo(Map.of());
    }

    @Test
    void list_protocols_harness_error_returns_structured_error() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.harness.payloads.put("list_protocols",
                Map.of("ok", false, "error", "harness_unavailable", "detail", "评测服务不可达"));
        AgentTool tool = AgentScenario.toolsByName(scenario.buildContext()).get("list_protocols");
        Map<String, Object> result = tool.invoke(Map.of());
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("harness_unavailable");
    }

    @Test
    void list_cases_passthrough_and_unknown_protocol_propagates() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.harness.payloads.put("list_cases", Fixtures.payload("LIST_CASES_DNS"));
        AgentTool tool = AgentScenario.toolsByName(scenario.buildContext()).get("list_cases");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", "dns");
        Map<String, Object> result = tool.invoke(args);
        assertThat(data(result).get("protocol")).isEqualTo("dns");
        assertThat(data(result).get("count")).isEqualTo(2);

        scenario.harness.payloads.put("list_cases",
                Map.of("ok", false, "error", "unknown_protocol", "detail", "未知协议"));
        Map<String, Object> failure = tool.invoke(args);
        assertThat(failure.get("ok")).isEqualTo(false);
        assertThat(failure.get("error")).isEqualTo("unknown_protocol");
    }

    @Test
    void plugin_template_returns_skeleton_without_mcp() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        AgentToolContext context = scenario.buildContext();
        AgentTool tool = AgentScenario.toolsByName(context).get("plugin_template");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", "dns");
        Map<String, Object> result = tool.invoke(args);
        assertThat(result.get("ok")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = data(result);
        assertThat((String) data.get("template")).contains("can_handle");
        assertThat((List<String>) data.get("checklist")).isNotEmpty();
        assertThat(scenario.harness.calls).isEmpty(); // 骨架本地生成，不触达 harness
    }

    // ------------------------------------------------------------------
    // B6 执行源带外
    // ------------------------------------------------------------------

    @Test
    void uploaded_source_is_executed_not_model_restatement() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "帮我评测这个插件";
        scenario.attachment = new AgentOrchestrator.Attachment("stu_dns.c", "dns", UPLOADED_SOURCE);
        AgentToolContext context = scenario.buildContext();
        assertThat(context.uploadedSource).isEqualTo(UPLOADED_SOURCE);
        assertThat(context.pastedSource).isNull(); // 有附件时 uploaded_source 是权威，不再抽取

        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result).get("source_origin")).isEqualTo("upload");
        assertThat(scenario.harness.callsOf("grade_plugin").get(0).get("args"))
                .isEqualTo(Map.of("protocol", "dns", "plugin_source", UPLOADED_SOURCE,
                        "timeout_sec", 120));
        // 成绩 code_hash = 上传原件字节
        assertThat(scenario.record.submissions).hasSize(1);
        assertThat(scenario.record.submissions.get(0).get("codeHash")).isEqualTo(sha256(UPLOADED_SOURCE));
        assertThat(scenario.record.submissions.get(0).get("sourceOrigin")).isEqualTo("upload");
    }

    @Test
    void pasted_fenced_source_is_executed_not_model_args() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "帮我评测 dns 插件：\n```c\n" + PASTED_SOURCE + "```";
        AgentToolContext context = scenario.buildContext();
        assertThat(context.pastedSource).isEqualTo(PASTED_SOURCE);

        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result).get("source_origin")).isEqualTo("pasted");
        assertThat(scenario.harness.callsOf("grade_plugin").get(0).get("args"))
                .isEqualTo(Map.of("protocol", "dns", "plugin_source", PASTED_SOURCE,
                        "timeout_sec", 120));
        assertThat(scenario.record.submissions.get(0).get("codeHash")).isEqualTo(sha256(PASTED_SOURCE));
    }

    @Test
    void fallback_source_beats_model_args_on_recovery() throws Exception {
        // S3-c：恢复重驱动时 fallback_source（首评寄存的权威字节）优先于模型复述。
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "继续评测";
        scenario.taskContext = new AgentOrchestrator.TaskContext("task_1", PASTED_SOURCE, null);
        AgentToolContext context = scenario.buildContext();
        assertThat(context.fallbackSource).isEqualTo(PASTED_SOURCE);

        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(data(result).get("source_origin")).isEqualTo("model_args");
        // MCP 收到寄存字节而非复述字节；确定性 job_id 含寄存源的 hash。
        assertThat(scenario.harness.callsOf("grade_plugin").get(0).get("args"))
                .isEqualTo(Map.of("protocol", "dns", "plugin_source", PASTED_SOURCE,
                        "timeout_sec", 120, "job_id",
                        "task_1:dns:" + sha256(PASTED_SOURCE).substring(0, 16)));
        assertThat(scenario.ledger.rows).hasSize(1);
        assertThat(scenario.ledger.backfilledSources).hasSize(1);
    }

    @Test
    void no_fenced_source_falls_back_to_model_args() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "帮我评测这段代码（正文复述）";
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(data(result).get("source_origin")).isEqualTo("model_args");
        assertThat(scenario.harness.callsOf("grade_plugin").get(0).get("args"))
                .isEqualTo(Map.of("protocol", "dns", "plugin_source", MODEL_ARG_SOURCE,
                        "timeout_sec", 120));
        assertThat(scenario.record.submissions.get(0).get("codeHash"))
                .isEqualTo(sha256(MODEL_ARG_SOURCE));
    }

    @Test
    void extract_fenced_source_picks_longest_and_handles_unclosed() {
        assertThat(FencedSource.extract("普通问题，没有代码")).isNull();
        assertThat(FencedSource.extract("")).isNull();
        // 多个块取内容最长者
        String text = "```\nint a;\n```\n中间文字\n```c\n" + PASTED_SOURCE + "int extra;\n```";
        assertThat(FencedSource.extract(text)).isEqualTo(PASTED_SOURCE + "int extra;\n");
        // 未闭合尾围栏：其后全部内容视为源码
        assertThat(FencedSource.extract("评测：\n```c\n" + PASTED_SOURCE)).isEqualTo(PASTED_SOURCE);
        // ``` 块内的 ~~~ 不算闭合（反向引用配对）
        assertThat(FencedSource.extract("```c\nint a; /* ~~~ */\n```")).isEqualTo("int a; /* ~~~ */\n");
        // ~~~ 围栏同口径
        assertThat(FencedSource.extract("~~~c\n" + PASTED_SOURCE + "~~~")).isEqualTo(PASTED_SOURCE);
    }

    @Test
    void invalid_utf8_replacement_aligns_size_and_hash() {
        // 无效字节策略：Java getBytes(UTF_8) 对不成对代理项替换为 '?'，
        // 与 Python errors="replace" 的 U+FFFD 落点差异已在交接文档登记；
        // 此处锁定"替换后字节参与大小校验与 SHA256"这一不变量。
        String broken = "插件源码 \uD800 修复"; // 孤立高代理项
        byte[] bytes = broken.getBytes(StandardCharsets.UTF_8);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\uD800");
        assertThat(AgentTools.sha256Hex(bytes)).hasSize(64);
    }

    // ------------------------------------------------------------------
    // 配额与大小
    // ------------------------------------------------------------------

    @Test
    void grade_plugin_rejects_oversized_source() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.settings = new AgentSettings(5, 48000, true, 24000, 120, 16, 20,
                3, true, true, 20000, "m");
        AgentTool grade = AgentScenario.toolsByName(scenario.buildContext()).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", PASTED_SOURCE);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("code_too_large");
        assertThat(scenario.harness.calls).isEmpty();
        assertThat(scenario.record.submissions).isEmpty();
    }

    @Test
    void grade_plugin_counts_daily_quota_and_exemption() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.settings = new AgentSettings(5, 48000, true, 24000, 120, 262144, 1,
                3, true, true, 20000, "m");
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(scenario.quota.charges).hasSize(1);

        // 第二次（同源）命中去重，不再扣；换源码则真的超限
        Map<String, Object> again = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(data(again)).containsEntry("deduplicated", true);
        assertThat(scenario.quota.charges).hasSize(1);
        Map<String, Object> other = invoke(grade, "dns", MODEL_ARG_SOURCE + "// v2\n");
        assertThat(other.get("ok")).isEqualTo(false);
        assertThat(other.get("error")).isEqualTo("quota_exceeded");
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);

        // 豁免角色不扣配额
        AgentScenario exempt = AgentScenario.create();
        exempt.question = "评测";
        exempt.settings = scenario.settings;
        exempt.roles = List.of("operator");
        AgentTool exemptGrade = AgentScenario.toolsByName(exempt.buildContext()).get("grade_plugin");
        Map<String, Object> result = invoke(exemptGrade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(exempt.quota.charges).isEmpty();
    }

    @Test
    void quota_exceeded_is_friendly_and_blocks_harness() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.quota.remaining = 0;
        scenario.quota = new FakeGradingPorts.Quota() {
            @Override
            public void checkAndCountDaily(String subject, int quota, String kind) {
                throw new QuotaExceededException("今日评测配额已用完");
            }
        };
        AgentTool grade = AgentScenario.toolsByName(scenario.buildContext()).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("quota_exceeded");
        assertThat(String.valueOf(result.get("detail"))).contains("明天再试");
        assertThat(scenario.harness.calls).isEmpty();
        assertThat(scenario.record.submissions).isEmpty();
    }

    // ------------------------------------------------------------------
    // B7 去重
    // ------------------------------------------------------------------

    @Test
    void same_source_deduplicated_before_quota() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");

        Map<String, Object> first = invoke(grade, "dns", MODEL_ARG_SOURCE);
        Map<String, Object> second = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(first.get("ok")).isEqualTo(true);
        assertThat(data(first)).doesNotContainKey("deduplicated");
        assertThat(data(second)).containsEntry("deduplicated", true);
        // 除标记外载荷与首次完全一致
        Map<String, Object> stripped = new LinkedHashMap<>(data(second));
        stripped.remove("deduplicated");
        assertThat(stripped).isEqualTo(data(first));
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(1);
        assertThat(context.gradingResults).hasSize(1);
        assertThat(scenario.record.submissions).hasSize(1);
        assertThat(scenario.quota.charges).hasSize(1);
    }

    @Test
    void dedup_key_uses_execution_source_not_model_args() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测：\n```c\n" + PASTED_SOURCE + "```";
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        invoke(grade, "dns", PASTED_SOURCE);
        Map<String, Object> second = invoke(grade, "dns", "模型这次复述错了 int x;");
        assertThat(data(second)).containsEntry("deduplicated", true);
        assertThat(scenario.harness.calls).hasSize(1); // 执行源同为粘贴原件，参数差异不参与去重键
    }

    @Test
    void different_source_or_protocol_not_deduplicated() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        invoke(grade, "dns", MODEL_ARG_SOURCE);
        Map<String, Object> other = invoke(grade, "dns", MODEL_ARG_SOURCE + "// v2\n");
        assertThat(data(other)).doesNotContainKey("deduplicated");
        Map<String, Object> third = invoke(grade, "http", MODEL_ARG_SOURCE);
        assertThat(data(third)).doesNotContainKey("deduplicated");
        assertThat(scenario.harness.callsOf("grade_plugin")).hasSize(3);
        assertThat(scenario.record.submissions).hasSize(3);
        assertThat(scenario.quota.charges).hasSize(3);
    }

    @Test
    void failed_grading_is_not_cached() throws Exception {
        // 失败结果不进入去重表：同一源码重试会真正重打 MCP。
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        FakeHarness flaky = FakeHarness.withPayloads(new LinkedHashMap<>());
        flaky.sequences.put("grade_plugin", new ArrayList<>(List.of(
                Map.of("ok", false, "error", "harness_unavailable", "detail", "评测服务不可达"),
                Fixtures.payload("HARNESS_OK"))));
        scenario.harness = flaky;
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");

        Map<String, Object> first = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(first.get("ok")).isEqualTo(false);
        assertThat(first.get("error")).isEqualTo("harness_unavailable");
        Map<String, Object> second = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(second.get("ok")).isEqualTo(true);
        assertThat(data(second)).doesNotContainKey("deduplicated");
        assertThat(flaky.callsOf("grade_plugin")).hasSize(2);
        assertThat(scenario.record.submissions).hasSize(1);
    }

    @Test
    void harness_transport_failure_propagates_as_exception() {
        // 传输层失败（连接被拒）不转结构化结果：与 Python mcp_caller 抛异常一致，
        // 由 run_tools 兜底为 tool_exception（TaskLedgerTest 验证任务路径转重驱动）。
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.harness = FakeHarness.unreachable(new IllegalStateException("connection refused"));
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        assertThatThrownBy(() -> invoke(grade, "dns", MODEL_ARG_SOURCE))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("connection refused");
        assertThat(scenario.record.submissions).isEmpty();
    }

    @Test
    void harness_error_payload_propagates_without_recording() throws Exception {
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.harness.payloads.put("grade_plugin",
                Map.of("ok", false, "error", "harness_unavailable", "detail", "评测服务不可达"));
        AgentTool grade = AgentScenario.toolsByName(scenario.buildContext()).get("grade_plugin");
        Map<String, Object> result = invoke(grade, "dns", MODEL_ARG_SOURCE);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("harness_unavailable");
        assertThat(scenario.record.submissions).isEmpty();
    }

    // ------------------------------------------------------------------
    // 事件摘要与标签
    // ------------------------------------------------------------------

    @Test
    void tool_event_labels_for_five_tools() {
        assertThat(io.dpdk.assistant.agent.tools.ToolSummaries.toolEventLabel("grade_plugin",
                Map.of("protocol", "dns"))).isEqualTo("正在评测 DNS 插件…");
        assertThat(io.dpdk.assistant.agent.tools.ToolSummaries.toolEventLabel("kb_search",
                Map.of("query", "qname 编码规则很长很长很长很长很长"))).startsWith("正在检索：");
        assertThat(io.dpdk.assistant.agent.tools.ToolSummaries.toolEventLabel("list_protocols",
                Map.of())).isEqualTo("正在查询协议列表…");
        assertThat(io.dpdk.assistant.agent.tools.ToolSummaries.toolEventLabel("list_cases",
                Map.of("protocol", "dns"))).isEqualTo("正在查询 DNS 的用例…");
        assertThat(io.dpdk.assistant.agent.tools.ToolSummaries.toolEventLabel("plugin_template",
                Map.of("protocol", "dns"))).isEqualTo("正在生成 DNS 插件骨架…");
    }

    @Test
    void tool_event_summaries_for_five_tools() {
        Map<String, Object> harnessOk = new LinkedHashMap<>();
        Map<String, Object> okData = new LinkedHashMap<>(Fixtures.payload("HARNESS_OK"));
        harnessOk.put("ok", true);
        harnessOk.put("data", okData);
        Map<String, Object> gradeOk = io.dpdk.assistant.agent.tools.ToolSummaries.toolEventSummary(
                "grade_plugin", PythonJson.dumps(harnessOk, false));
        assertThat(gradeOk.get("ok")).isEqualTo(true);
        assertThat(gradeOk.get("summary")).isEqualTo("8/8 通过，得分 100");

        Map<String, Object> kbOk = io.dpdk.assistant.agent.tools.ToolSummaries.toolEventSummary(
                "kb_search", "{\"ok\": true, \"data\": {\"query\": \"q\", \"count\": 3}}");
        assertThat(kbOk.get("summary")).isEqualTo("「q」命中 3 条资料");

        Map<String, Object> failure = io.dpdk.assistant.agent.tools.ToolSummaries.toolEventSummary(
                "list_cases", "{\"ok\": false, \"error\": \"unknown_protocol\", \"detail\": \"未知协议\"}");
        assertThat(failure.get("ok")).isEqualTo(false);
        assertThat(failure.get("summary")).isEqualTo("未知协议");

        Map<String, Object> malformed = io.dpdk.assistant.agent.tools.ToolSummaries.toolEventSummary(
                "kb_search", "not-json");
        assertThat(malformed.get("ok")).isEqualTo(false);
        assertThat(malformed.get("summary")).isEqualTo("工具返回格式异常");
    }

    // ------------------------------------------------------------------
    // 压缩摘要（P2 覆盖式摘要行为）
    // ------------------------------------------------------------------

    @Test
    void kb_context_summary_is_bounded_and_keeps_signature_at_any_offset() {
        String content = "第1段" + "长".repeat(300)
                + "\nint can_handle_0(const uint8_t *pkt);\n" + "长".repeat(120);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("ref", 1);
        chunk.put("source_path", "doc.md");
        chunk.put("section", "s");
        chunk.put("title", "t");
        chunk.put("content", content);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", "can_handle");
        data.put("count", 1);
        data.put("chunks", List.of(chunk));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("data", data);
        String result = io.dpdk.assistant.agent.tools.ToolSummaries.toolContextSummary(
                "kb_search", PythonJson.dumps(payload, false));
        assertThat(result).contains("top_chunks").contains("can_handle_0");
    }

    @Test
    void grade_context_summary_keeps_score_and_failures() {
        String result = io.dpdk.assistant.agent.tools.ToolSummaries.toolContextSummary(
                "grade_plugin", PythonJson.dumps(Fixtures.payload("HARNESS_FAIL"), false));
        assertThat(result).contains("score").contains("62");
        assertThat(result).contains("期望 example.com").contains("实际 www.example.com");
        assertThat(result).contains("已压缩为评分事实");
    }

    @Test
    void retryable_ledger_failure_wraps_as_retryable() {
        // 账本失败阻断评测：任务路径 findJob 故障 → RetryableTaskFailure。
        AgentScenario scenario = AgentScenario.create();
        scenario.question = "评测";
        scenario.taskContext = new AgentOrchestrator.TaskContext("task_1", null, null);
        scenario.ledger.findJobError = new IllegalStateException("db down");
        AgentToolContext context = scenario.buildContext();
        AgentTool grade = AgentScenario.toolsByName(context).get("grade_plugin");
        assertThatThrownBy(() -> invoke(grade, "dns", MODEL_ARG_SOURCE))
                .getCause().isInstanceOf(RetryableTaskFailure.class)
                .hasMessageContaining("task_job_ledger_unavailable");
    }

    @Test
    void fake_ledger_is_available_for_audit() {
        // 隔离测试账本保留供审计（回退条款）：FakeLedger 行为可复演。
        FakeLedger ledger = new FakeLedger();
        ledger.recordJob("t1", "j1", "grade_plugin", "dns", "hash");
        assertThat(ledger.rowByJobId("j1").get("status")).isEqualTo("submitted");
    }
}
