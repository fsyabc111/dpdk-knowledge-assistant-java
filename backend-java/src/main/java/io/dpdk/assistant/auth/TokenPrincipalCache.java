package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * resolve_token 的进程内正向缓存（token_hash → Principal，60s，单调时钟）。
 *
 * 语义对齐 user_service._token_cache：只缓存正向命中；任何吊销/改密/停号/改配额/
 * 改角色操作由调用方 clear() 整体清空，保证吊销立即生效（60s 缓存不掩盖撤销）。
 * 判定用 `now - storedAt < 60.0`（对齐 Python 的严格小于）。
 */
final class TokenPrincipalCache {

    private static final double TTL_SECONDS = 60.0;

    private record Entry(double storedAt, Principal principal) {
    }

    private final ClockPort clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    TokenPrincipalCache(ClockPort clock) {
        this.clock = clock;
    }

    Optional<Principal> get(String tokenHash) {
        Entry entry = entries.get(tokenHash);
        if (entry == null) {
            return Optional.empty();
        }
        if (monotonicSeconds() - entry.storedAt() < TTL_SECONDS) {
            return Optional.of(entry.principal());
        }
        entries.remove(tokenHash, entry);
        return Optional.empty();
    }

    void put(String tokenHash, Principal principal) {
        entries.put(tokenHash, new Entry(monotonicSeconds(), principal));
    }

    /** 清空全部正向缓存（吊销/凭据失效必须立即生效）。 */
    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private double monotonicSeconds() {
        return clock.monotonicNanos() / 1_000_000_000.0;
    }
}
