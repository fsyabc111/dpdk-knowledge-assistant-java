package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;

/**
 * 按学号的进程内登录锁定（对齐 api/auth.py::_LoginLockout）。
 *
 * check 在密码验证之前调用：锁定期内存在/不存在的学号应答完全一致
 * （429 `too many failed login attempts, try again later` + Retry-After，
 * Retry-After=max(1, int(until-now)+1)）。15 分钟滑窗内失败 5 次锁 15 分钟，
 * 成功登录清零；重启清零（进程内存语义）。
 */
public final class LoginLockout {

    private static final double WINDOW_SECONDS =
            AuthServerSettings.LOGIN_LOCKOUT_MINUTES * 60.0;

    private final ClockPort clock;
    private final ConcurrentHashMap<String, ArrayDeque<Double>> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> lockedUntil = new ConcurrentHashMap<>();

    public LoginLockout(ClockPort clock) {
        this.clock = clock;
    }

    /** 锁定中抛 429；锁定期自然结束则清除。 */
    public void check(String username) {
        double now = monotonicSeconds();
        Double until = lockedUntil.get(username);
        if (until == null) {
            return;
        }
        if (now < until) {
            double retryAfter = Math.max(1, (int) (until - now) + 1);
            throw new ApiDetailException(HttpStatus.TOO_MANY_REQUESTS,
                    "too many failed login attempts, try again later",
                    java.util.Map.of("Retry-After", String.valueOf((int) retryAfter)));
        }
        lockedUntil.remove(username, until);
    }

    public void recordFailure(String username) {
        double now = monotonicSeconds();
        ArrayDeque<Double> bucket = failures.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() >= WINDOW_SECONDS) {
                bucket.pollFirst();
            }
            bucket.addLast(now);
            if (bucket.size() >= AuthServerSettings.LOGIN_MAX_FAILURES) {
                lockedUntil.put(username, now + WINDOW_SECONDS);
                bucket.clear();
            }
        }
    }

    public void recordSuccess(String username) {
        failures.remove(username);
        lockedUntil.remove(username);
    }

    /** 测试/诊断：清空全部进程内状态。 */
    public void reset() {
        failures.clear();
        lockedUntil.clear();
    }

    private double monotonicSeconds() {
        return clock.monotonicNanos() / 1_000_000_000.0;
    }
}
