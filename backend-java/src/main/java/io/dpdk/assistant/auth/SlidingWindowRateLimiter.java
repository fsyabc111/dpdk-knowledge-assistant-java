package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.platform.ClockPort;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import io.dpdk.assistant.platform.errors.ApiDetailException;

/**
 * 登录/重置匿名接口的进程内滑动窗限流（对齐 api/auth.py::_LoginRateLimiter）。
 *
 * 独立成桶、不消耗聊天配额；窗口 60s、按 key（"login:{ip}" / "reset-request:{ip}" /
 * "reset-confirm:{ip}"）滑动；超限 429 detail=`{kind} rate limit exceeded ({limit}/minute)`
 * + Retry-After=max(1, int(60-(now-first))+1)。时间取 ClockPort 单调时钟（可控窗口边界）。
 */
public final class SlidingWindowRateLimiter {

    private static final double WINDOW_SECONDS = 60.0;

    private final int limit;
    private final String kind;
    private final ClockPort clock;
    private final ConcurrentHashMap<String, ArrayDeque<Double>> buckets = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int limit, String kind, ClockPort clock) {
        this.limit = limit;
        this.kind = kind;
        this.clock = clock;
    }

    /** 通过或抛 429（Retry-After 头）；通过即记录本次时刻。 */
    public void check(String key) {
        double now = monotonicSeconds();
        ArrayDeque<Double> bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() >= WINDOW_SECONDS) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                double retryAfter = Math.max(1, (int) (WINDOW_SECONDS - (now - bucket.peekFirst())) + 1);
                throw new ApiDetailException(HttpStatus.TOO_MANY_REQUESTS,
                        kind + " rate limit exceeded (" + limit + "/minute)",
                        java.util.Map.of("Retry-After", String.valueOf((int) retryAfter)));
            }
            bucket.addLast(now);
        }
    }

    /** 测试/诊断：清空全部桶（对齐 Python 测试对模块级限流器的复位）。 */
    public void reset() {
        buckets.clear();
    }

    private double monotonicSeconds() {
        return clock.monotonicNanos() / 1_000_000_000.0;
    }
}
