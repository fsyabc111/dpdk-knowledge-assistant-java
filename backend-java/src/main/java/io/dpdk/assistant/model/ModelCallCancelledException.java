package io.dpdk.assistant.model;

/**
 * 调用方在等待期间取消（线程中断）。已发送的请求不会被伪称为「远端已取消」：
 * 账本按 unknown 记一笔（上游可能已消耗算力），背景 IO 完成后资源自然释放。
 */
public class ModelCallCancelledException extends RuntimeException {

    public ModelCallCancelledException(String message) {
        super(message);
    }
}
