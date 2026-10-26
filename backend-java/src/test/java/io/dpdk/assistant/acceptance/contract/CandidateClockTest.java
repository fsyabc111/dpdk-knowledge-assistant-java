package io.dpdk.assistant.acceptance.contract;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deadline预算与持久事件分域；真实客户端仍按真实单调期限约束。 */
class CandidateClockTest {
    @Test void defaultFactoryConsumesOneWallReadingAndUsesMonotonicBudget() {
        AtomicInteger reads=new AtomicInteger();Instant base=Instant.parse("2026-10-24T04:00:00Z");
        ClockPort clock=new ClockPort(){public Instant nowUtc(){reads.incrementAndGet();return base;}public long monotonicNanos(){return 1000;}public String nextSessionIso(){throw new AssertionError();}};
        Deadline d=clock.deadlineAfter(Duration.ofSeconds(3));
        assertThat(d.wallDeadline()).isEqualTo(base.plusSeconds(3));assertThat(d.monotonicNanosDeadline()).isEqualTo(3_000_001_000L);assertThat(reads.get()).isEqualTo(1);
    }
    @Test void candidatePeekDoesNotConsumePersistentTickAndRetainsRealBudget() {
        var clock=new CandidateServer.FixedClock();Instant first=clock.nowUtc();long before=System.nanoTime();Deadline d=clock.deadlineAfter(Duration.ofSeconds(2));long after=System.nanoTime();Instant next=clock.nowUtc();
        assertThat(next).isEqualTo(first.plusNanos(1000));assertThat(d.wallDeadline()).isEqualTo(next.plusSeconds(2));assertThat(d.monotonicNanosDeadline()).isBetween(before+2_000_000_000L,after+2_000_000_000L);assertThat(clock.calls()).hasSize(2);
    }
    @Test void expiredCallerBudgetSubmitsNothing()throws Exception {
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/mcp",e->{requests.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});server.start();
        try {var reply=new StreamableHttpHarnessClient("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp").call("grade_plugin",Map.of(),new CandidateServer.FixedClock().deadlineAfter(Duration.ofSeconds(-1)));
            assertThat(reply.submissionPhase()).isEqualTo(HarnessReply.SubmissionPhase.NOT_SUBMITTED);assertThat(reply.error()).isEqualTo("harness_deadline_exceeded");assertThat(requests.get()).isZero();
        }finally{server.stop(0);}
    }
    @Test void callerBudgetDoesNotInterruptAnAlreadySubmittedRequest()throws Exception {
        AtomicInteger submits=new AtomicInteger();CountDownLatch submitted=new CountDownLatch(1),release=new CountDownLatch(1);ObjectMapper json=new ObjectMapper();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mcp",e->{try{
            var req=json.readTree(e.getRequestBody());String method=req.path("method").asText();Object id=json.convertValue(req.path("id"),Object.class);Map<String,Object> result;
            if(method.equals("initialize"))result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"serverInfo",Map.of("name","synthetic","version","1"));
            else if(method.startsWith("notifications/")){e.sendResponseHeaders(202,-1);return;}
            else {submits.incrementAndGet();submitted.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release missing");result=Map.of("content",List.of(Map.of("type","text","text","{\"ok\":true,\"score\":80}")),"isError",false);}
            byte[] bytes=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"result",result));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
        }catch(Exception failed){e.sendResponseHeaders(500,-1);}finally{e.close();}});server.start();
        try(var caller=Executors.newVirtualThreadPerTaskExecutor()){
            Deadline deadline=new CandidateServer.FixedClock().deadlineAfter(Duration.ofSeconds(2));
            var future=caller.submit(()->new StreamableHttpHarnessClient("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp").call("grade_plugin",Map.of(),deadline));
            assertThat(submitted.await(2,TimeUnit.SECONDS)).isTrue();long remaining=deadline.monotonicNanosDeadline()-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining+10_000_000L);release.countDown();
            var reply=future.get(3,TimeUnit.SECONDS);assertThat(reply.submissionPhase()).isEqualTo(HarnessReply.SubmissionPhase.SUBMITTED);assertThat(submits.get()).isEqualTo(1);assertThat(json.readTree(reply.payloadJson()).path("score").asInt()).isEqualTo(80);
        }finally{release.countDown();server.stop(0);}
    }
}
