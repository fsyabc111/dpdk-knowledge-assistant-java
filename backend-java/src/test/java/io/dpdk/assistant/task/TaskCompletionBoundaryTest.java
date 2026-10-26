package io.dpdk.assistant.task;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 收尾发生于真实finish之后且对外终态前；异常与取消同样运行，JDBC不带interrupt。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class TaskCompletionBoundaryTest {
    @Test void success() throws Exception { exercise("success"); }
    @Test void failure() throws Exception { exercise("failure"); }
    @Test void cancellation() throws Exception { exercise("cancel"); }
    private void exercise(String mode) throws Exception {
        var h=TaskTestHarness.create("completion");
        try {
            String user="U20270000011",session="sess_555555bbbbbb";
            h.seedUser(user,"[\"viewer\"]",100);h.seedSession(session,user);
            var admitted=h.service.admitDetail(new AdmissionInput(new RequestIdentity(UUID.randomUUID().toString(),
                    TaskInputHash.compute(mode),false,RequestIdentity.Purpose.chat),user,session,mode,"","synthetic",mode,100));
            String task=admitted.row().orElseThrow().id();
            var entered=new CountDownLatch(1);var completionEntered=new CountDownLatch(1);
            var releaseCompletion=new CountDownLatch(1);var calls=new AtomicInteger();
            var executor=new TaskExecutor() {
                public TaskExecutionResult run(TaskExecutionContext context,Consumer<TaskEvent> emit) throws Exception {
                    entered.countDown();
                    if (mode.equals("cancel")) new CountDownLatch(1).await();
                    if (mode.equals("failure")) throw new IllegalStateException("synthetic failure");
                    return new TaskExecutionResult("answer","answered","supported",List.of(),"");
                }
                public void afterCompletion(TaskExecutionContext context) {
                    calls.incrementAndGet();assertFalse(Thread.currentThread().isInterrupted());
                    var row=h.store.findOwned(task,user).orElseThrow();
                    assertEquals(mode.equals("success")?"answered":mode.equals("cancel")?"cancelled":"pending",row.status());
                    completionEntered.countDown();
                    try { assertTrue(releaseCompletion.await(5,TimeUnit.SECONDS)); }
                    catch(InterruptedException e) { throw new AssertionError(e); }
                }
            };
            h.gate.acquire();
            var context=new TaskExecutor.TaskExecutionContext(task,admitted.row().orElseThrow().requestId(),session,
                    user,List.of("viewer"),"chat",mode,"",null,null,null,null);
            var managed=h.taskRunner.startReserved(user,context.requestId(),session,context,executor);
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                if(mode.equals("cancel")) {
                    h.store.cancelTask(task,user,"user_cancelled");managed.future().cancel(true);
                }
                assertTrue(completionEntered.await(5,TimeUnit.SECONDS));
                assertFalse(managed.isTerminal());
            } finally { releaseCompletion.countDown(); }
            assertTrue(managed.awaitDone(Duration.ofSeconds(5)));assertEquals(1,calls.get());
        } finally { h.drop(); }
    }
}
