package io.dpdk.assistant.agent.tools;

import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.port.QuotaExceededException;
import io.dpdk.assistant.agent.port.TaskGradePort;
import io.dpdk.assistant.contracts.grading.TaskGradeInput;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;
import io.dpdk.assistant.retrieval.PythonJson;
import io.dpdk.assistant.template.TemplateService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 五工具（kb_search / list_protocols / list_cases / plugin_template /
 * grade_plugin；源 backend/app/agent/tools.py，轮内执行顺序照源）。
 *
 * <p>统一约定：每个工具返回 {"ok": true, "data": ...} 或
 * {"ok": false, "error": <code>, "detail": <可读原因>}，不向外抛异常；账本/
 * 成绩持久化失败抛 {@link RetryableTaskFailure}（产品任务保留输入并重驱动）。
 * 工具副作用（检索/harness/配额/账本/成绩）全部经 {@link AgentToolContext}
 * 注入端口调用，Java 图与工具层不直连存储。
 */
public final class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private AgentTools() {
    }

    /** 构建绑定到 context 的请求级工具集；顺序与 Python build_tools 返回一致。 */
    public static List<AgentTool> buildTools(AgentToolContext context) {
        return List.of(
                kbSearchTool(context),
                listProtocolsTool(context),
                listCasesTool(context),
                pluginTemplateTool(context),
                gradePluginTool(context));
    }

    // ------------------------------------------------------------------
    // 参数解包（Python 签名缺参 → TypeError → tool_exception 口径）
    // ------------------------------------------------------------------

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return s;
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s : "";
    }

    // ------------------------------------------------------------------
    // kb_search
    // ------------------------------------------------------------------

    private static AgentTool kbSearchTool(AgentToolContext context) {
        return new AgentTool() {
            @Override
            public String name() {
                return "kb_search";
            }

            @Override
            public String description() {
                return "检索项目知识库，回答协议原理、插件接口、项目结构等事实性问题。"
                        + "参数 query：检索关键词或问题。返回编号片段列表，作答时用 [编号] 引用。";
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args) {
                String query = requireString(args, "query");
                context.searchAttempted = true;
                List<KbChunk> chunks;
                try {
                    chunks = context.retriever.search(query, 8, context.conversationContext);
                } catch (Exception exc) {
                    log.warn(PythonJson.dumps(Map.of(
                            "event", "agent_tool_error", "tool", "kb_search",
                            "error_type", exc.getClass().getSimpleName()), false));
                    return failure("retrieval_failed", "知识库检索暂时不可用");
                }
                // 同一片段只入账一次，重复命中复用原编号；编号必须按 collected_chunks
                // 的位置发（引用校验按位置，去重只能发生在追加之前）。
                Map<String, Integer> refs = new HashMap<>();
                for (int index = 0; index < context.collectedChunks.size(); index++) {
                    refs.put(context.collectedChunks.get(index).chunkId(), index + 1);
                }
                List<Map<String, Object>> dataChunks = new ArrayList<>();
                for (KbChunk chunk : chunks) {
                    Integer ref = refs.get(chunk.chunkId());
                    if (ref == null) {
                        context.collectedChunks.add(chunk);
                        ref = context.collectedChunks.size();
                        refs.put(chunk.chunkId(), ref);
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("ref", ref);
                    entry.put("source_path", chunk.sourcePath());
                    entry.put("section", chunk.section());
                    entry.put("title", chunk.title());
                    entry.put("content", CodePoints.truncate(chunk.content(),
                            AgentToolContext.KB_CHUNK_CONTENT_LIMIT));
                    dataChunks.add(entry);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("query", query);
                data.put("chunks", dataChunks);
                data.put("count", dataChunks.size());
                return ok(data);
            }
        };
    }

    // ------------------------------------------------------------------
    // list_protocols / list_cases
    // ------------------------------------------------------------------

    private static AgentTool listProtocolsTool(AgentToolContext context) {
        return new AgentTool() {
            @Override
            public String name() {
                return "list_protocols";
            }

            @Override
            public String description() {
                return "列出评测环境（harness）支持的协议清单，含每个协议的用例数与用例类别分布。"
                        + "无参数。学生问\"有哪些实验/协议可以做\"时使用。";
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args) throws Exception {
                Map<String, Object> payload = context.harness.call("list_protocols", Map.of(),
                        context.settings.gradingTimeoutSec());
                if (!okFlag(payload)) {
                    return failure(strOr(payload.get("error"), "list_protocols_failed"),
                            strOr(payload.get("detail"), "协议清单查询失败"));
                }
                List<Map<String, Object>> protocols = new ArrayList<>();
                if (payload.get("protocols") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", m.get("name"));
                            entry.put("cases", m.get("cases") != null ? m.get("cases") : 0);
                            entry.put("categories", m.get("categories") != null ? m.get("categories") : Map.of());
                            protocols.add(entry);
                        }
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("protocols", protocols);
                data.put("count", protocols.size());
                return ok(data);
            }
        };
    }

    private static AgentTool listCasesTool(AgentToolContext context) {
        return new AgentTool() {
            @Override
            public String name() {
                return "list_cases";
            }

            @Override
            public String description() {
                return "列出某协议的全部评测用例（名称 + 类别标签）。"
                        + "参数 protocol：协议名（如 dns、http、ssh）。学生问\"某协议测哪些用例/场景\"时使用。";
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args) throws Exception {
                String protocol = requireString(args, "protocol");
                Map<String, Object> payload = context.harness.call("list_cases", Map.of("protocol", protocol),
                        context.settings.gradingTimeoutSec());
                if (!okFlag(payload)) {
                    return failure(strOr(payload.get("error"), "list_cases_failed"),
                            strOr(payload.get("detail"), "用例清单查询失败"));
                }
                List<Map<String, Object>> cases = new ArrayList<>();
                if (payload.get("cases") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", m.get("name"));
                            entry.put("category", m.get("category") != null ? m.get("category") : "");
                            cases.add(entry);
                        }
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("protocol", payload.get("protocol") != null ? payload.get("protocol") : protocol);
                data.put("cases", cases);
                data.put("count", cases.size());
                return ok(data);
            }
        };
    }

    // ------------------------------------------------------------------
    // plugin_template
    // ------------------------------------------------------------------

    private static AgentTool pluginTemplateTool(AgentToolContext context) {
        return new AgentTool() {
            @Override
            public String name() {
                return "plugin_template";
            }

            @Override
            public String description() {
                return "生成某协议的学习用插件骨架（保留 TODO 与 checklist），是起手代码而非作业答案。"
                        + "参数 protocol：协议名（如 dns、http、ssh）。学生要\"骨架/模板/起手代码\"时使用；"
                        + "返回骨架代码、解释、检查清单与参考文件路径。";
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args) {
                String protocol = requireString(args, "protocol");
                TemplateService.TemplateResult generated;
                try {
                    generated = TemplateService.generate(protocol);
                } catch (Exception exc) {
                    log.warn(PythonJson.dumps(Map.of(
                            "event", "agent_tool_error", "tool", "plugin_template",
                            "error_type", exc.getClass().getSimpleName()), false));
                    return failure("template_failed", "插件骨架生成暂时不可用");
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("protocol", protocol);
                data.put("template", generated.template());
                data.put("explanation", generated.explanation());
                data.put("checklist", generated.checklist());
                data.put("references", generated.references());
                return ok(data);
            }
        };
    }

    // ------------------------------------------------------------------
    // grade_plugin（执行源带外 / 去重 / 账本）
    // ------------------------------------------------------------------

    private static AgentTool gradePluginTool(AgentToolContext context) {
        return new AgentTool() {
            @Override
            public String name() {
                return "grade_plugin";
            }

            @Override
            public String description() {
                return "评测学生的协议检测插件源码，返回逐用例结果与分数。"
                        + "参数 protocol：协议名（如 dns、http、ssh）。"
                        + "参数 plugin_source：插件 .c 源码全文（用户消息中的 C 代码块或上传的 .c 文件内容）。"
                        + "若本请求已有上传/粘贴的源码，以服务端寄存的为准——模型在参数里"
                        + "复述的源码可能与原件字节不一致（B6 执行源带外寄存）。";
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args) throws Exception {
                String modelArgsSource = requireString(args, "plugin_source");
                String protocol = requireString(args, "protocol");
                return gradePlugin(context, protocol, modelArgsSource);
            }
        };
    }

    /** grade_plugin 主体（package 可见便于单测直调）。 */
    static Map<String, Object> gradePlugin(AgentToolContext context, String protocol,
                                           String modelArgsSource) throws Exception {
        AgentSettings settings = context.settings;
        // B6：执行源解析顺序 = 上传原件 > 消息里抽取的粘贴块 > 恢复寄存源 > 模型参数复述。
        String execSource;
        String sourceOrigin;
        if (context.uploadedSource != null) {
            execSource = context.uploadedSource;
            sourceOrigin = "upload";
        } else if (context.pastedSource != null) {
            execSource = context.pastedSource;
            sourceOrigin = "pasted";
        } else if (context.fallbackSource != null) {
            execSource = context.fallbackSource;
            sourceOrigin = "model_args";
        } else {
            execSource = modelArgsSource;
            sourceOrigin = "model_args";
        }
        byte[] execBytes = execSource.getBytes(StandardCharsets.UTF_8);
        String codeHash = sha256Hex(execBytes);

        // B7：同请求内同 (protocol, 执行源) 的重复评测直接复用首次结果——
        // 先于配额扣减与 MCP 调用，不重复记账、不重复落库。表里只有成功载荷。
        String dedupKey = protocol + ":" + codeHash;
        Map<String, Object> cached = context.gradedSources.get(dedupKey);
        if (cached != null) {
            Map<String, Object> data = new LinkedHashMap<>(cached);
            data.put("deduplicated", true);
            return ok(data);
        }

        // ---- S3-c 作业账本：仅任务上下文（taskId 非空）启用 ----
        // 接续先于配额扣减：账本已有 submitted 行的作业属同一提交，恢复不二次扣。
        Ledger ledger = null;
        if (context.taskId != null) {
            try {
                ledger = resumeGradingJob(context, protocol, execBytes);
            } catch (Exception exc) {
                log.warn(PythonJson.dumps(Map.of(
                        "event", "task_job_ledger_failed", "tool", "grade_plugin",
                        "error_type", exc.getClass().getSimpleName()), false));
                throw new RetryableTaskFailure("task_job_ledger_unavailable", exc);
            }
            if (ledger.replayed != null) {
                String saved = recordTaskJobGrade(context, ledger, protocol, sourceOrigin, ledger.replayed);
                if ("cancelled".equals(saved)) {
                    return failure("task_cancelled", "任务已取消");
                }
                context.gradingResults.add(ledger.replayed);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("protocol", strOr(ledger.replayed.get("protocol"), protocol));
                data.put("score", ledger.replayed.get("score"));
                data.put("passed", ledger.replayed.get("passed"));
                data.put("cases_total", ledger.replayed.get("cases_total"));
                data.put("results", orList(ledger.replayed.get("results")));
                data.put("source_origin", sourceOrigin);
                data.put("resumed", true);
                context.gradedSources.put(dedupKey, data);
                return ok(data);
            }
        }

        if (execBytes.length > settings.gradingMaxCodeBytes()) {
            return failure("code_too_large",
                    "源码大小超过上限（" + settings.gradingMaxCodeBytes() + " 字节）");
        }
        if (ledger == null) {
            if (!context.quotaExempt()) {
                try {
                    context.gradingQuota.checkAndCountDaily(context.userId,
                            settings.gradingDailyQuota(), "grading");
                } catch (QuotaExceededException e) {
                    return failure("quota_exceeded",
                            "今日评测配额已用完（" + settings.gradingDailyQuota() + " 次/天），请明天再试");
                }
            }
        } else {
            try {
                // 首次 job 登记与评测日配额同事务；恢复命中旧行则不二次扣。
                int quota = context.quotaExempt() || ledger.resumed ? 0 : settings.gradingDailyQuota();
                context.taskLedger.reserveGradingJob(context.taskId, ledger.jobId, protocol,
                        ledger.codeHash, context.userId, quota);
                if ("model_args".equals(sourceOrigin)) {
                    // 复述路径的执行源首评才确定：回填 inputs，恢复拿到同一份字节。
                    context.taskLedger.updateTaskSource(context.taskId, execSource, sourceOrigin);
                }
            } catch (QuotaExceededException e) {
                return failure("quota_exceeded",
                        "今日评测配额已用完（" + settings.gradingDailyQuota() + " 次/天），请明天再试");
            } catch (Exception exc) {
                log.warn(PythonJson.dumps(Map.of(
                        "event", "task_job_record_failed", "tool", "grade_plugin",
                        "error_type", exc.getClass().getSimpleName()), false));
                throw new RetryableTaskFailure("task_job_record_failed", exc);
            }
        }

        Map<String, Object> mcpArgs = new LinkedHashMap<>();
        mcpArgs.put("protocol", protocol);
        mcpArgs.put("plugin_source", execSource);
        mcpArgs.put("timeout_sec", settings.gradingTimeoutSec());
        if (ledger != null) {
            // 同 job_id 幂等语义在 harness 侧：运行中加入既有执行、已完成回放结果。
            mcpArgs.put("job_id", ledger.jobId);
        }
        Map<String, Object> payload = context.harness.call("grade_plugin", mcpArgs,
                settings.gradingTimeoutSec());
        if (!okFlag(payload)) {
            return failure(strOr(payload.get("error"), "grading_failed"),
                    strOr(payload.get("detail"), "评测服务返回失败"));
        }
        if (ledger != null) {
            try {
                context.taskLedger.finishJob(ledger.jobId, payload);
            } catch (Exception exc) {
                log.warn(PythonJson.dumps(Map.of(
                        "event", "task_job_finish_failed", "tool", "grade_plugin",
                        "error_type", exc.getClass().getSimpleName()), false));
                throw new RetryableTaskFailure("task_job_finish_failed", exc);
            }
            String saved = recordTaskJobGrade(context, ledger, protocol, sourceOrigin, payload);
            if ("cancelled".equals(saved)) {
                return failure("task_cancelled", "任务已取消");
            }
        } else {
            recordSubmissionBestEffort(context, protocol, execSource, sourceOrigin, payload);
        }
        context.gradingResults.add(payload);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("protocol", strOr(payload.get("protocol"), protocol));
        data.put("score", payload.get("score"));
        data.put("passed", payload.get("passed"));
        data.put("cases_total", payload.get("cases_total"));
        data.put("results", orList(payload.get("results")));
        // 让模型能如实说明评的是哪份源（上传原件/粘贴块/参数复述）。
        data.put("source_origin", sourceOrigin);
        context.gradedSources.put(dedupKey, data);
        return ok(data);
    }

    // ------------------------------------------------------------------
    // S3-c 账本接续与成绩落库
    // ------------------------------------------------------------------

    private record Ledger(String jobId, String codeHash, boolean resumed,
                          Map<String, Object> replayed) {
    }

    /**
     * S3-c 评测作业账本接续（只在 taskId 上下文启用）。
     * 接续顺序：账本行 finished → 直接复用；submitted → get_task_status 接续
     * （finished 复用、running/unknown 交回主流程以同一 job_id 调 grade_plugin）；
     * 账本缺行 → get_task_status 兜底，兜住「harness 有结果而账本漏写」的场景。
     * 任何账本层异常向上抛（调用方转 RetryableTaskFailure，任务不得降级为无账本评测）。
     */
    static Ledger resumeGradingJob(AgentToolContext context, String protocol, byte[] execBytes)
            throws Exception {
        String codeHash = sha256Hex(execBytes);
        String deterministicJobId = context.taskId + ":" + protocol + ":" + codeHash.substring(0, 16);
        Map<String, Object> job = context.taskLedger.findJob(context.taskId, protocol, codeHash);
        Map<String, Object> replayed = null;
        String jobId = deterministicJobId;
        boolean resumed;
        if (job != null) {
            jobId = GradeFacts.str(job.get("job_id"));
            resumed = true;
            if ("finished".equals(job.get("status"))) {
                replayed = jobResultPayload(job);
            } else {
                Map<String, Object> status = harnessJobStatus(context, jobId);
                if (status != null && "finished".equals(status.get("status"))
                        && status.get("result") instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) status.get("result");
                    context.taskLedger.finishJob(jobId, result);
                    replayed = result;
                }
            }
        } else {
            resumed = false;
            Map<String, Object> status = harnessJobStatus(context, jobId);
            if (status != null && "finished".equals(status.get("status"))
                    && status.get("result") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) status.get("result");
                context.taskLedger.recordJob(context.taskId, jobId, "grade_plugin", protocol, codeHash);
                context.taskLedger.finishJob(jobId, result);
                replayed = result;
            }
        }
        // resumed 语义：账本已有该行 → 恢复接续（主流程不再二次扣评测配额）。
        resumed = resumed || replayed != null;
        return new Ledger(jobId, codeHash, resumed, replayed);
    }

    /** get_task_status 探测：失败/超时/形态异常一律 null（按不可接续处理，主流程重投）。 */
    private static Map<String, Object> harnessJobStatus(AgentToolContext context, String jobId) {
        try {
            Map<String, Object> payload = context.harness.call("get_task_status",
                    Map.of("job_id", jobId), HarnessCaller.statusTimeoutSeconds(context.settings));
            if (!okFlag(payload)) {
                return null;
            }
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    /** 账本行 result_json → dict 载荷；损坏/缺行按 null（交回重投路径）。 */
    private static Map<String, Object> jobResultPayload(Map<String, Object> job) {
        Object raw = job.get("result_json");
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(s, new com.fasterxml.jackson.core.type.TypeReference<Object>() {
                    });
            return parsed instanceof Map<?, ?> m ? castMap(m) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 任务成绩与账本标志同事务（J2-04 冻结契约）；DB 故障抛可重驱动异常。
     */
    static String recordTaskJobGrade(AgentToolContext context, Ledger ledger, String protocol,
                                     String sourceOrigin, Map<String, Object> payload) {
        try {
            List<Map<String, Object>> results = orList(payload.get("results"));
            Map<String, Object> resultsDoc = new LinkedHashMap<>();
            resultsDoc.put("source_origin", sourceOrigin);
            resultsDoc.put("results", results);
            TaskGradeInput input = new TaskGradeInput(
                    context.taskId, ledger.jobId,
                    GradeFacts.str(strOr(payload.get("protocol"), protocol)),
                    ledger.codeHash,
                    (int) Math.round(GradeFacts.intOf(payload.get("score"), 0)),
                    GradeFacts.intOf(payload.get("passed"), 0),
                    GradeFacts.intOf(payload.get("cases_total"), 0),
                    PythonJson.dumps(resultsDoc, false));
            TaskGradeResult result = context.taskGrade.recordTaskGrade(input);
            if (result == TaskGradeResult.legacy_unlinked) {
                log.warn(PythonJson.dumps(Map.of(
                        "event", "task_job_legacy_grade_unlinked",
                        "job_id", ledger.jobId()), false));
            }
            return result.name();
        } catch (Exception exc) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "task_job_grade_save_failed", "job_id", ledger.jobId(),
                    "error_type", exc.getClass().getSimpleName()), false));
            throw new RetryableTaskFailure("task_job_grade_save_failed", exc);
        }
    }

    /** Best-effort persistence; a DB hiccup must not fail the grading reply. */
    private static void recordSubmissionBestEffort(AgentToolContext context, String protocol,
                                                   String execSource, String sourceOrigin,
                                                   Map<String, Object> payload) {
        try {
            context.gradingRecord.recordSubmission(context.userId, context.sessionId,
                    GradeFacts.str(strOr(payload.get("protocol"), protocol)),
                    sha256Hex(execSource.getBytes(StandardCharsets.UTF_8)),
                    (int) Math.round(GradeFacts.intOf(payload.get("score"), 0)),
                    GradeFacts.intOf(payload.get("passed"), 0),
                    GradeFacts.intOf(payload.get("cases_total"), 0),
                    orList(payload.get("results")), sourceOrigin);
        } catch (Exception exc) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "grading_record_failed",
                    "error_type", exc.getClass().getSimpleName()), false));
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static boolean okFlag(Map<String, Object> payload) {
        return payload != null && Boolean.TRUE.equals(payload.get("ok"));
    }

    static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", data);
        return out;
    }

    static Map<String, Object> failure(String error, String detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("detail", detail);
        return out;
    }

    static String strOr(Object value, String fallback) {
        return value == null || String.valueOf(value).isEmpty() ? fallback : String.valueOf(value);
    }

    public static List<Map<String, Object>> orList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add(castMap(m));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** 安全解析 JSON 对象；解析失败/非对象返回 null（lastGradedProtocol 容错用）。 */
    public static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Object>() {
                    });
            return parsed instanceof Map<?, ?> m ? castMap(m) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
