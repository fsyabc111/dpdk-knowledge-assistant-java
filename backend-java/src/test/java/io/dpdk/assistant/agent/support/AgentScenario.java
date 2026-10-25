package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentOrchestrator;
import io.dpdk.assistant.agent.AgentRunListener;
import io.dpdk.assistant.agent.AgentOrchestrator.AgentRunResult;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.agent.tools.HarnessCaller;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 黄金场景运行器：把脚本化模型 + 假 harness/检索/账本接到真实编排路径。
 * 对位 golden_support.build_orchestrator + collect_stream 的合集。
 */
public final class AgentScenario {

    public ScriptedChatModel model;
    public FakeHarness harness;
    public FakeRetriever retriever;
    public FakeGradingPorts.Quota quota = new FakeGradingPorts.Quota();
    public FakeGradingPorts.Record record = new FakeGradingPorts.Record();
    public FakeGradingPorts.Stats stats = new FakeGradingPorts.Stats();
    public FakeGradingPorts.TaskGrade taskGrade = new FakeGradingPorts.TaskGrade();
    public FakeLedger ledger = new FakeLedger();
    public FakeGradingPorts.Usage usage = new FakeGradingPorts.Usage();
    public AgentSettings settings = AgentSettings.defaults();
    public String userId = "stu_1";
    public List<String> roles = List.of("viewer");
    public String conversationContext;
    public AgentOrchestrator.TaskContext taskContext;
    public String question;
    public AgentOrchestrator.Attachment attachment;
    public AgentOrchestrator.MemoryView memory;
    public RecordingListener events = new RecordingListener();

    public static AgentScenario create() {
        AgentScenario scenario = new AgentScenario();
        scenario.model = new ScriptedChatModel(ScriptedChatModel.text("占位答复"));
        scenario.harness = new FakeHarness();
        scenario.retriever = new FakeRetriever();
        return scenario;
    }

    /** 构建并运行；返回结果（events 里是全量事件）。 */
    public AgentRunResult run() {
        AgentToolContext context = buildContext();
        AgentOrchestrator orchestrator = new AgentOrchestrator(model, settings);
        return orchestrator.run(userId, roles, null, question, attachment, memory,
                conversationContext, taskContext, events, context);
    }

    public AgentToolContext buildContext() {
        HarnessCaller caller = new HarnessCaller(harness, settings, usage);
        return AgentOrchestrator.buildContext(settings, userId, roles, "sess_1", question,
                attachment, new AgentOrchestrator.Deps(retriever, caller, record, quota,
                        stats, taskGrade, ledger, usage),
                taskContext == null ? null : taskContext.taskId(),
                taskContext == null ? null : taskContext.fallbackSource());
    }

    /** 事件记录器（tool_start/tool_end/delta/sources/error）。 */
    public static final class RecordingListener implements AgentRunListener {
        public record ToolEnd(String tool, boolean ok, String summary) {
        }

        public final List<String> deltas = new ArrayList<>();
        public final List<String> toolStarts = new ArrayList<>();
        public final List<ToolEnd> toolEnds = new ArrayList<>();
        public final List<List<String>> sourcesRanks = new ArrayList<>();

        @Override
        public void onDelta(String text) {
            deltas.add(text);
        }

        @Override
        public void onToolStart(String tool, String label) {
            toolStarts.add(tool + "|" + label);
        }

        @Override
        public void onToolEnd(Map<String, Object> summary) {
            toolEnds.add(new ToolEnd(String.valueOf(summary.get("tool")),
                    Boolean.TRUE.equals(summary.get("ok")),
                    String.valueOf(summary.get("summary"))));
        }

        @Override
        public void onSources(List<io.dpdk.assistant.agent.port.KbChunk> chunks) {
            List<String> ids = new ArrayList<>();
            chunks.forEach(chunk -> ids.add(chunk.chunkId()));
            sourcesRanks.add(ids);
        }
    }

    // ------------------------------------------------------------------
    // 工具调用脚本 helper（对位 kb_call / grade_call 等）
    // ------------------------------------------------------------------

    public static io.dpdk.assistant.agent.ToolCallSpec kbCall(String query, String callId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        return io.dpdk.assistant.agent.ToolCallSpec.of(callId, "kb_search", args);
    }

    public static io.dpdk.assistant.agent.ToolCallSpec listProtocolsCall(String callId) {
        return io.dpdk.assistant.agent.ToolCallSpec.of(callId, "list_protocols", Map.of());
    }

    public static io.dpdk.assistant.agent.ToolCallSpec listCasesCall(String protocol, String callId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", protocol);
        return io.dpdk.assistant.agent.ToolCallSpec.of(callId, "list_cases", args);
    }

    public static io.dpdk.assistant.agent.ToolCallSpec templateCall(String protocol, String callId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", protocol);
        return io.dpdk.assistant.agent.ToolCallSpec.of(callId, "plugin_template", args);
    }

    public static io.dpdk.assistant.agent.ToolCallSpec gradeCall(String protocol, String source,
                                                                 String callId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", protocol);
        args.put("plugin_source", source);
        return io.dpdk.assistant.agent.ToolCallSpec.of(callId, "grade_plugin", args);
    }

    /** 直接构建五工具集（场景 4 的工具面断言）。 */
    public static List<String> toolNames(AgentToolContext context) {
        List<String> names = new ArrayList<>();
        io.dpdk.assistant.agent.tools.AgentTools.buildTools(context)
                .forEach(tool -> names.add(tool.name()));
        return names;
    }

    /** 工具名→工具映射。 */
    public static Map<String, io.dpdk.assistant.agent.tools.AgentTool> toolsByName(AgentToolContext context) {
        Map<String, io.dpdk.assistant.agent.tools.AgentTool> out = new LinkedHashMap<>();
        io.dpdk.assistant.agent.tools.AgentTools.buildTools(context).forEach(t -> out.put(t.name(), t));
        return out;
    }

    /** 供断言的模型回复构造（tool_call + usage）。 */
    public static AgentChatModel.AgentReply usage(int total, io.dpdk.assistant.agent.ToolCallSpec... calls) {
        return new AgentChatModel.AgentReply("", List.of(calls),
                new io.dpdk.assistant.agent.AgentUsage(0, 0, total));
    }


    private AgentScenario() {
    }
}
