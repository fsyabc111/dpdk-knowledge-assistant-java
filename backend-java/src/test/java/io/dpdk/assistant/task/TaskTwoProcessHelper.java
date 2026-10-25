package io.dpdk.assistant.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 双进程恢复验证的第二进程入口（真 JVM，不是 mock）。
 *
 * 用法：TaskTwoProcessHelper <database> <mode> [taskId] [releaseFile]
 * mode=scan          → 恢复扫描一拍，stdout 输出 RECOVERED=<n>
 * mode=recover-hold  → 恢复扫描并重驱动，等待 releaseFile 出现后放行执行体收口，
 *                      stdout 输出 RECOVERED=<n> 与 STATUS=<行状态>
 *
 * 执行体为文件门控脚本（模型/Agent 外部边界替身）；身份/事务/账本全真实。
 */
public final class TaskTwoProcessHelper {

    public static void main(String[] args) throws Exception {
        String database = args[0];
        String mode = args[1];
        TaskTestHarness h = TaskTestHarness.attach(database);
        TaskTwoProcessHelper helper = new TaskTwoProcessHelper(h);
        switch (mode) {
            case "scan" -> {
                int recovered = h.service.recoverOrphans();
                System.out.println("RECOVERED=" + recovered);
                System.out.println("DONE");
            }
            case "recover-hold" -> {
                String taskId = args[2];
                Path release = Path.of(args[3]);
                h.setRecoveryExecutor((context, emit) -> {
                    emit.accept(TaskEvent.of("status", java.util.Map.of("stage", "helper-running")));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    while (!Files.exists(release) && System.nanoTime() < deadline) {
                        Thread.sleep(50);
                    }
                    emit.accept(TaskEvent.of("status", java.util.Map.of("stage", "helper-final")));
                    return new TaskExecutor.TaskExecutionResult("双进程恢复答案", "answered",
                            "supported",
                            java.util.List.of(new CitationData("li_helper", "docs/h.md", "§1")),
                            "release-helper");
                });
                int recovered = h.service.recoverOrphans();
                System.out.println("RECOVERED=" + recovered);
                // 等执行收口（执行体受 releaseFile 门控）
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                while (System.nanoTime() < deadline) {
                    String status = h.columnValue("rag_requests", "status", "id=?", taskId);
                    if (!"pending".equals(status)) {
                        System.out.println("STATUS=" + status);
                        break;
                    }
                    Thread.sleep(100);
                }
                System.out.println("DONE");
            }
            default -> {
                System.out.println("UNKNOWN-MODE=" + mode);
                System.exit(2);
            }
        }
        h.taskRunner.shutdownWatchdog();
        System.exit(0);
    }

    private final TaskTestHarness h;

    TaskTwoProcessHelper(TaskTestHarness h) {
        this.h = h;
    }
}
