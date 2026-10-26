package io.dpdk.assistant.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 上游用量记录核心（对齐 backend/app/core/usage.py 的纯逻辑子集；
 * 不碰数据库、不发网络请求，落库在 {@link UsageLedgerStore}）。
 *
 * 三条不可动摇语义（测试锁死）：
 * 1. 未知不是 0：上游没返回 usage 时 token 列一律 null，绝不用 0 顶替，也不做本地估算；
 * 2. 零调用要留痕：缓存命中/幂等重放/本地规则显式记 call_count=0 条目；
 * 3. 未定价不出价：模型不在价格表 → 成本 null，只报用量（写入时定价并盖 price_version）。
 */
public final class UsageCore {

    /* ---- 用量来源 ---- */
    public static final String SOURCE_UPSTREAM = "upstream";
    public static final String SOURCE_UNKNOWN = "unknown";
    public static final String SOURCE_CACHE_HIT = "cache_hit";
    public static final String SOURCE_REPLAY = "replay";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_OPERATION = "operation";

    public static final Set<String> SOURCES = Set.of(SOURCE_UPSTREAM, SOURCE_UNKNOWN,
            SOURCE_CACHE_HIT, SOURCE_REPLAY, SOURCE_LOCAL, SOURCE_OPERATION);

    /** 已知的零调用：没有 token 数是事实而非缺失，不计入 unknown。 */
    public static final Set<String> ZERO_CALL_SOURCES = Set.of(SOURCE_CACHE_HIT, SOURCE_REPLAY,
            SOURCE_LOCAL);

    /* ---- 用量种类 ---- */
    public static final String KIND_LLM = "llm";
    public static final String KIND_EMBEDDING = "embedding";
    public static final String KIND_HARNESS = "harness";
    public static final String KIND_MCP = "mcp";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);

    private UsageCore() {
    }

    /* ------------------------------------------------------------------ */
    /* 上游 usage 解析                                                     */
    /* ------------------------------------------------------------------ */

    public record TokenTriple(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /** _int_or_none：非负整数；任何异常形态（含 bool、负数、非整数）一律 null。 */
    static Integer intOrNone(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof Integer i) {
            return i >= 0 ? i : null;
        }
        if (value instanceof Long l) {
            return l >= 0 && l <= Integer.MAX_VALUE ? l.intValue() : null;
        }
        if (value instanceof Double d) {
            return d >= 0 && d == Math.floor(d) && d <= Integer.MAX_VALUE ? d.intValue() : null;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.matches("\\d+") ? tryParseInt(trimmed) : null;
        }
        return null;
    }

    private static Integer tryParseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * tokens_from_usage：兼容 prompt_tokens/completion_tokens 与 LangChain
     * usage_metadata 的 input_tokens/output_tokens 两套键名；只给分项时
     * total=prompt+completion 是等价推导（任一分项缺失保持 null）。
     */
    public static TokenTriple tokensFromUsage(Map<String, Object> usage) {
        if (usage == null) {
            return new TokenTriple(null, null, null);
        }
        Integer prompt = intOrNone(firstNonNull(usage.get("prompt_tokens"), usage.get("input_tokens")));
        Integer completion = intOrNone(firstNonNull(usage.get("completion_tokens"),
                usage.get("output_tokens")));
        Integer total = intOrNone(usage.get("total_tokens"));
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        return new TokenTriple(prompt, completion, total);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /* ------------------------------------------------------------------ */
    /* 价格                                                                */
    /* ------------------------------------------------------------------ */

    /** 单价（每 1000 token，字符串精确解析；embedding 可省 output 按 0 计）。 */
    public record ModelPrice(BigDecimal inputPer1k, BigDecimal outputPer1k, String currency) {
    }

    static BigDecimal decimalOrNone(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number || value instanceof String) {
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
            return parsed.signum() >= 0 ? parsed : null;
        }
        return null;
    }

    /** parse_prices（LLM_PRICES_JSON）：空/非法 JSON → {}；单条不合法跳过不抛。 */
    public static Map<String, ModelPrice> parsePrices(String raw) {
        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return prices;
        }
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return prices;
        }
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            String model = entry.getKey();
            if (!(model instanceof String) || model.isBlank()
                    || !(entry.getValue() instanceof Map<?, ?> spec)) {
                continue;
            }
            BigDecimal inputPrice = decimalOrNone(spec.get("input_per_1k"));
            if (inputPrice == null) {
                continue;
            }
            BigDecimal outputPrice = decimalOrNone(spec.get("output_per_1k"));
            Object currency = spec.get("currency");
            if (!(currency instanceof String c) || c.isBlank()) {
                continue;
            }
            prices.put(model.strip(), new ModelPrice(inputPrice,
                    outputPrice == null ? BigDecimal.ZERO : outputPrice, c.strip()));
        }
        return prices;
    }

    /** cost_micros：整数微单位（ROUND_HALF_UP）；未配价或两 token 全缺 → null。 */
    public static Integer costMicros(Integer promptTokens, Integer completionTokens,
                                     ModelPrice price) {
        if (price == null) {
            return null;
        }
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        if (promptTokens != null) {
            total = total.add(BigDecimal.valueOf(promptTokens)
                    .multiply(price.inputPer1k()).divide(BigDecimal.valueOf(1000)));
        }
        if (completionTokens != null) {
            total = total.add(BigDecimal.valueOf(completionTokens)
                    .multiply(price.outputPer1k()).divide(BigDecimal.valueOf(1000)));
        }
        return total.multiply(MICROS).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /* ------------------------------------------------------------------ */
    /* 条目与累加器                                                        */
    /* ------------------------------------------------------------------ */

    /** 一次上游花费（或显式零调用）；token null 表示未知。 */
    public record UsageEntry(String kind, String stage, String usageSource, String model,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens,
                             int callCount, Integer durationMs) {

        public boolean priced() {
            return UsageAggregate.PRICED_KINDS.contains(kind);
        }

        public boolean usageKnown() {
            return totalTokens != null;
        }
    }

    /** 请求级用量累加器：只记录，不落库（归属只填空缺不覆盖）。 */
    public static final class UsageRecorder {

        private final List<UsageEntry> entries = new ArrayList<>();
        private String subject;
        private String sessionId;
        private int producers;
        private int deliveries;
        private boolean checkpointed;
        private boolean checkpointAcknowledged;
        private boolean sealed;
        private Throwable deliveryFailure;
        private java.util.function.Consumer<UsageEntry> lateReceiver;
        private final java.util.concurrent.CompletableFuture<Void> receiptsDone = new java.util.concurrent.CompletableFuture<>();

        public UsageRecorder() {
        }

        public UsageRecorder(String subject, String sessionId) {
            this.subject = subject;
            this.sessionId = sessionId;
        }

        public synchronized void bind(String subject, String sessionId) {
            if (subject != null && !subject.isEmpty() && this.subject == null) {
                this.subject = subject;
            }
            if (sessionId != null && !sessionId.isEmpty() && this.sessionId == null) {
                this.sessionId = sessionId;
            }
        }

        public synchronized String subject() {
            return subject;
        }

        public synchronized String sessionId() {
            return sessionId;
        }

        public synchronized List<UsageEntry> entries() {
            return List.copyOf(entries);
        }

        /** 记一次真实上游调用：usage 缺字段 → 该字段未知。 */
        public UsageEntry recordLlm(String stage, String model, Map<String, Object> usage,
                                     Integer durationMs, String kind, int calls) {
            TokenTriple tokens = tokensFromUsage(usage);
            String source = tokens.promptTokens() != null || tokens.completionTokens() != null
                    || tokens.totalTokens() != null ? SOURCE_UPSTREAM : SOURCE_UNKNOWN;
            return append(new UsageEntry(kind, stage, source, model, tokens.promptTokens(),
                    tokens.completionTokens(), tokens.totalTokens(), Math.max(calls, 0),
                    durationMs));
        }

        public UsageEntry recordLlm(String stage, String model, Map<String, Object> usage) {
            return recordLlm(stage, model, usage, null, KIND_LLM, 1);
        }

        /** 零上游调用（缓存/重放/本地规则）：call_count=0；未知 reason 降级 unknown。 */
        public UsageEntry recordSkipped(String stage, String reason, String model, String kind) {
            String source = ZERO_CALL_SOURCES.contains(reason) ? reason : SOURCE_UNKNOWN;
            return append(new UsageEntry(kind, stage, source, model, null, null, null, 0, null));
        }

        /** 非 token 用量（harness/MCP）：只记次数与耗时，不计费。 */
        public UsageEntry recordOperation(String kind, String stage, Integer durationMs, int calls) {
            return append(new UsageEntry(kind, stage, SOURCE_OPERATION, null, null, null, null,
                    Math.max(calls, 0), durationMs));
        }

        /**
         * 在异步生产者交给线程池之前取lease，避免候选尚未启动就被取消时丢收尾归属。
         * lease只持有计量对象，不持有问题、源码或任务上下文；关闭恰好一次。
         */
        public synchronized ProducerLease retainProducer() {
            if (sealed) throw new IllegalStateException("用量收据已收尾，禁止新生产者");
            producers++;
            return new ProducerLease();
        }

        public final class ProducerLease implements AutoCloseable {
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            private ProducerLease() {}
            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;
                synchronized (UsageRecorder.this) {
                    producers--;
                }
                completeIfDrained();
            }
        }

        /**
         * 原子切开历史快照和迟到增量；sink永远在recorder锁外运行。
         * 调用方写完快照后ack，completion再等全部生产者及迟到sink退出。
         */
        public synchronized Checkpoint checkpointAndSubscribe(java.util.function.Consumer<UsageEntry> receiver) {
            if (checkpointed) throw new IllegalStateException("用量收尾只能登记一次");
            lateReceiver = java.util.Objects.requireNonNull(receiver);
            checkpointed = true;
            return new Checkpoint(List.copyOf(entries));
        }

        public final class Checkpoint {
            private final List<UsageEntry> snapshot;
            private final java.util.concurrent.atomic.AtomicBoolean acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
            private Checkpoint(List<UsageEntry> snapshot) { this.snapshot = snapshot; }
            public List<UsageEntry> entries() { return snapshot; }
            public java.util.concurrent.CompletionStage<Void> completion() { return receiptsDone.minimalCompletionStage(); }
            public void acknowledge() { finish(null); }
            public void fail(Throwable failure) { finish(java.util.Objects.requireNonNull(failure)); }
            private void finish(Throwable failure) {
                if (!acknowledged.compareAndSet(false, true)) return;
                synchronized (UsageRecorder.this) {
                    checkpointAcknowledged = true;
                    if (failure != null && deliveryFailure == null) deliveryFailure = failure;
                }
                completeIfDrained();
            }
        }

        private UsageEntry append(UsageEntry entry) {
            java.util.function.Consumer<UsageEntry> receiver;
            synchronized (this) {
                if (sealed) throw new IllegalStateException("用量收据已封存，生产者lease缺失");
                entries.add(entry);
                receiver = lateReceiver;
                if (receiver != null) deliveries++;
            }
            if (receiver != null) {
                try { receiver.accept(entry); }
                catch (RuntimeException failure) {
                    // 计量失败不覆盖答案/取消；但可等ack必须显式失败，不能谎称已持久化。
                    synchronized (this) { if (deliveryFailure == null) deliveryFailure = failure; }
                } finally {
                    synchronized (this) { deliveries--; }
                    completeIfDrained();
                }
            }
            return entry;
        }

        /** drain后释放归账闭包；completion依赖回调也在monitor外执行。 */
        private void completeIfDrained() {
            Throwable failure;
            synchronized (this) {
                if (sealed || !checkpointed || !checkpointAcknowledged || producers != 0 || deliveries != 0) return;
                sealed = true;
                lateReceiver = null;
                failure = deliveryFailure;
            }
            if (failure == null) receiptsDone.complete(null);
            else receiptsDone.completeExceptionally(failure);
        }

        /** 紧凑汇总（chat_trace 日志口径；明细不进日志）。 */
        public synchronized Map<String, Object> totals() {
            List<UsageEntry> priced = entries.stream().filter(UsageEntry::priced).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entries", entries.size());
            out.put("calls", entries.stream().mapToInt(UsageEntry::callCount).sum());
            out.put("prompt_tokens", sumOf(priced, UsageEntry::promptTokens));
            out.put("completion_tokens", sumOf(priced, UsageEntry::completionTokens));
            out.put("total_tokens", sumOf(priced, UsageEntry::totalTokens));
            out.put("total_tokens_entries", priced.stream()
                    .filter(e -> e.totalTokens() != null).count());
            out.put("unknown_entries", entries.stream()
                    .filter(e -> SOURCE_UNKNOWN.equals(e.usageSource())).count());
            out.put("zero_call_entries", entries.stream()
                    .filter(e -> ZERO_CALL_SOURCES.contains(e.usageSource())).count());
            out.put("stages", entries.stream().map(UsageEntry::stage).distinct().sorted().toList());
            return out;
        }

        private static Integer sumOf(List<UsageEntry> entries,
                                     java.util.function.Function<UsageEntry, Integer> field) {
            List<Integer> present = new ArrayList<>();
            for (UsageEntry entry : entries) {
                Integer value = field.apply(entry);
                if (value != null) {
                    present.add(value);
                }
            }
            return present.isEmpty() ? null
                    : present.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 条目 → 账本行                                                       */
    /* ------------------------------------------------------------------ */

    /** entries_to_rows：成本写入时算好并盖 price_version（调价不回溯历史账）。 */
    public static List<UsageLedgerRow> entriesToRows(List<UsageEntry> entries,
                                                     Map<String, ModelPrice> prices,
                                                     String priceVersion, String requestId,
                                                     String subject, String sessionId,
                                                     String createdAt) {
        List<UsageLedgerRow> rows = new ArrayList<>();
        for (UsageEntry entry : entries) {
            ModelPrice price = entry.model() == null ? null : prices.get(entry.model());
            boolean priced = entry.priced();
            Integer cost = priced ? costMicros(entry.promptTokens(), entry.completionTokens(),
                    price) : null;
            String currency = priced && price != null ? price.currency() : null;
            String version = priced && price != null ? priceVersion : null;
            rows.add(new UsageLedgerRow("use_" + hex12(), requestId, subject, sessionId,
                    entry.kind(), entry.stage(), entry.model(), entry.promptTokens(),
                    entry.completionTokens(), entry.totalTokens(), entry.usageSource(),
                    entry.callCount(), entry.durationMs(), cost, currency, version, createdAt));
        }
        return rows;
    }

    static String hex12() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
