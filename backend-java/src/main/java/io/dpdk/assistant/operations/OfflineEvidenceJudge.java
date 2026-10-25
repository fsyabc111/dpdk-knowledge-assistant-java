package io.dpdk.assistant.operations;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.chat.AnswerValidator;
import io.dpdk.assistant.usage.UsageCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Python rag_judge_eval 的内嵌答案/证据裁决；不读取服务凭据，不改在线判据或持久账本。 */
public final class OfflineEvidenceJudge implements Function<Path, Map<String, Object>> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AgentChatModel model;
    private final Duration timeout;
    private final boolean modelJudgeEnabled;

    public OfflineEvidenceJudge(AgentChatModel model, Duration timeout, boolean modelJudgeEnabled) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("judge timeout 必须为正数");
        timeout.toNanos(); // 拒绝超出可执行计时范围的配置。
        this.model = model; this.timeout = timeout; this.modelJudgeEnabled = modelJudgeEnabled;
    }

    @Override public Map<String, Object> apply(Path path) {
        List<Map<String, Object>> cases = load(path);
        if (modelJudgeEnabled && model==null && cases.stream().anyMatch(c -> text(c.getOrDefault("mode", "judge")).equals("judge")))
            throw new IllegalStateException("已启用模型 judge 但模型未装配");
        List<Map<String, Object>> records = new ArrayList<>();
        int deterministicFailures=0, modelMismatched=0, skipped=0, mismatched=0, matched=0, errors=0;
        Map<String, Integer> distribution = new LinkedHashMap<>(), confusion = new LinkedHashMap<>();
        for (Map<String, Object> item : cases) {
            String mode = text(item.getOrDefault("mode", "judge"));
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("name", text(item.get("name"))); record.put("mode", mode);
            record.put("expect_status", item.get("expect_status")); record.put("expect_reason", item.get("expect_reason"));
            record.put("actual_status", null); record.put("actual_reason", null); record.put("matched", null);
            record.put("error", ""); record.put("elapsed_sec", 0.0);
            UsageCore.UsageRecorder usage = new UsageCore.UsageRecorder();
            if (mode.equals("judge") && !modelJudgeEnabled) {
                record.put("outcome", "skipped_live_not_authorized"); skipped++;
                record.put("usage", totals(usage)); records.add(record); continue;
            }
            long started = System.nanoTime();
            String[] failure = {""};
            try {
                AnswerValidator.Judge judge = mode.equals("judge") ? (question, answer, evidence, limit) -> {
                    try { return call(question, answer, evidence, limit, usage); }
                    catch (Exception e) {
                        // 异常消息可能包含候选源码、prompt 或凭据，仅报告类型。
                        Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause()!=null ? e.getCause() : e;
                        failure[0] = cause.getClass().getSimpleName(); throw e;
                    }
                } : null;
                var verdict = AnswerValidator.validate(text(item.get("question")), text(item.get("answer")), evidence(item), judge, timeout);
                record.put("actual_status", verdict.status()); record.put("actual_reason", verdict.reason());
                if (verdict.status().equals("system_error") && failure[0].isEmpty()) failure[0]="ValidationFailed";
                boolean equal = verdict.status().equals(item.get("expect_status"))
                        && (item.get("expect_reason")==null || verdict.reason().equals(item.get("expect_reason")));
                record.put("matched", equal); record.put("outcome", equal ? "matched" : "mismatched");
                record.put("error", failure[0]);
                if (equal) matched++; else {
                    mismatched++;
                    if (failure[0].isEmpty()) {
                        if (mode.equals("deterministic")) deterministicFailures++; else modelMismatched++;
                    }
                    confusion.merge(text(item.get("expect_status"))+"->"+verdict.status(),1,Integer::sum);
                }
                distribution.merge(verdict.status(),1,Integer::sum);
            } catch (Exception e) {
                record.put("matched", false); record.put("outcome", "error"); record.put("error", e.getClass().getSimpleName());
                mismatched++;
            }
            if (!text(record.get("error")).isEmpty()) errors++;
            record.put("elapsed_sec", (System.nanoTime()-started)/1e9);
            record.put("usage", totals(usage)); records.add(record);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", records.size()); summary.put("matched", matched); summary.put("mismatched", mismatched);
        summary.put("skipped", skipped); summary.put("runtime_errors", errors);
        summary.put("deterministic_mismatched", deterministicFailures); summary.put("model_mismatched", modelMismatched);
        summary.put("status_distribution", distribution); summary.put("confusion", confusion);
        summary.put("usage", summarizeUsage(records));
        return Map.of("domain", "offline_evidence_judge", "model_judge_enabled", modelJudgeEnabled,
                "summary", summary, "cases", records, "exit_code", 0,
                "result", errors>0 ? "error" : mismatched>0 ? "mismatched" : skipped>0 ? "incomplete" : "matched");
    }

    private Map<String, Object> call(String question, String answer, Map<String, Object> evidence,
            Duration limit, UsageCore.UsageRecorder usage) throws Exception {
        if (model == null) throw new IllegalStateException("模型 judge 未装配");
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var future = workers.submit(() -> model.call(List.of(AgentMessage.system(AnswerValidator.JUDGE_PROMPT),
                AgentMessage.human(JSON.writeValueAsString(Map.of("question", question,"answer", answer,"evidence", evidence)))), List.of()));
        Map<String, Object> tokens = new LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            var reply = future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
            if (reply.usage().promptTokens()!=null) tokens.put("prompt_tokens",reply.usage().promptTokens());
            if (reply.usage().completionTokens()!=null) tokens.put("completion_tokens",reply.usage().completionTokens());
            if (reply.usage().totalTokens()!=null) tokens.put("total_tokens",reply.usage().totalTokens());
            if (!reply.toolCalls().isEmpty()) throw new IllegalArgumentException("judge 不接受工具调用");
            return JSON.readValue(reply.content(),new TypeReference<Map<String,Object>>() {});
        } finally {
            future.cancel(true); workers.shutdownNow();
            usage.recordLlm("judge", "configured_offline_judge", tokens,
                    (int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-started)/1_000_000), UsageCore.KIND_LLM,1);
        }
    }

    private static List<Map<String, Object>> load(Path path) {
        if (path==null || !path.isAbsolute()) throw new IllegalArgumentException("题集必须使用显式绝对路径");
        final Object raw;
        try {
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            raw = new Yaml(new SafeConstructor(options)).load(Files.readString(path));
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("题集无法安全加载"); }
        if (!(raw instanceof Map<?,?> root) || !(root.get("cases") instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException("题集必须含非空 cases 数组");
        List<Map<String, Object>> cases = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (Object value:list) {
            if (!(value instanceof Map<?,?> item)) throw new IllegalArgumentException("case 必须为对象");
            Map<String,Object> row = new LinkedHashMap<>();
            item.forEach((key,v)->row.put(String.valueOf(key),v));
            String mode=text(row.getOrDefault("mode","judge"));
            if (!Set.of("deterministic","judge").contains(mode) || text(row.get("name")).isBlank()
                    || !names.add(text(row.get("name"))) || !(row.get("expect_status") instanceof String))
                throw new IllegalArgumentException("case 的 name/mode/expect_status 无效");
            if (row.get("question")!=null && !(row.get("question") instanceof String)
                    || row.get("answer")!=null && !(row.get("answer") instanceof String)
                    || row.get("expect_reason")!=null && !(row.get("expect_reason") instanceof String))
                throw new IllegalArgumentException("case 文本字段类型无效");
            evidence(row); cases.add(row);
        }
        return cases;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> evidence(Map<String,Object> row) {
        Object value=row.get("evidence");
        if (value==null) return Map.of();
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("evidence 必须为对象");
        for(String key:List.of("chunks","tools")) {
            Object entries=map.get(key);
            if(entries!=null && !(entries instanceof List<?>)) throw new IllegalArgumentException("evidence 数组字段类型无效");
        }
        Map<String,Object> out=new LinkedHashMap<>((Map<String,Object>)map);
        out.putIfAbsent("chunks",List.of());out.putIfAbsent("tools",List.of()); return out;
    }
    private static String text(Object value) { return value==null ? "" : String.valueOf(value); }
    private static Map<String,Object> totals(UsageCore.UsageRecorder usage) {
        Map<String,Object> totals=usage.totals();
        if (Integer.valueOf(0).equals(totals.get("calls")))
            for(String key:List.of("prompt_tokens","completion_tokens","total_tokens")) totals.put(key,0);
        return totals;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> summarizeUsage(List<Map<String,Object>> records) {
        Map<String,Object> summary=new LinkedHashMap<>();
        for(String key:List.of("calls","prompt_tokens","completion_tokens","total_tokens","unknown_entries")) {
            long sum=0;boolean known=false,called=false;
            for(var record:records) {
                var usage=(Map<String,Object>)record.get("usage");
                boolean hasCall=((Number)usage.get("calls")).longValue()>0;
                called|=hasCall;
                Object value=usage.get(key);
                if(value instanceof Number n && (hasCall || !key.endsWith("tokens"))) {sum+=n.longValue();known=true;}
            }
            summary.put(key,known || !called ? sum : null);
        }
        return summary;
    }
}
