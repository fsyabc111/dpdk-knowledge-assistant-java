package io.dpdk.assistant.model;

import java.util.concurrent.Semaphore;

/**
 * 上游并发门（对齐 backend/app/core/upstream_gate.py 的 upstream_slot）。
 *
 * Python 按事件循环持有信号量；Java 以进程内单实例对位，由客户端注册表共享。
 * 每次上游 HTTP 尝试都要持有许可（try-with-resources），异常/取消路径同样释放。
 */
public final class UpstreamGate {

    private final Semaphore permits;
    private final int concurrency;

    public UpstreamGate(int concurrency) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("rag_upstream_concurrency 必须为正数");
        }
        this.concurrency = concurrency;
        this.permits = new Semaphore(concurrency);
    }

    public int concurrency() {
        return concurrency;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /** 获取一个上游许可；可被线程中断（取消等待不占用许可）。 */
    public Permit acquire() throws InterruptedException {
        permits.acquire();
        return new Permit();
    }

    /** 许可作为资源使用：无论成功、失败还是取消都必须 close 归还。 */
    public final class Permit implements AutoCloseable {
        private boolean released;

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                permits.release();
            }
        }
    }
}
