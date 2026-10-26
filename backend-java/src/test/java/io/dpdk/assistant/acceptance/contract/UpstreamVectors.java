package io.dpdk.assistant.acceptance.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
import io.dpdk.assistant.model.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;

/** 14外部协议脚本：HTTP故障仅在模型/MCP边界注入，调用生产客户端。无PG。 */
final class UpstreamVectors {
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args)throws Exception {
        var rows=run();java.nio.file.Path out=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectories(out.toAbsolutePath().getParent());java.nio.file.Files.writeString(out,JSON.writeValueAsString(rows));System.out.println("UPSTREAM_VECTORS="+rows.size());
    }
    static void response(com.sun.net.httpserver.HttpExchange e,int status,String type,String body)throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
    }
    /** 首两轮真正TCP拒绝，第三轮透明转发到真实MCP HTTP替身。 */
    static final class DelayedProxy implements AutoCloseable {
        final int port;final java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket> sockets=new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile java.net.ServerSocket listener;volatile boolean closed;
        DelayedProxy(int target)throws Exception {
            try(var reserve=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=reserve.getLocalPort();}
            Thread.ofVirtual().start(()->{try{
                Thread.sleep(1100);if(closed)return;listener=new java.net.ServerSocket(port,20,java.net.InetAddress.getLoopbackAddress());
                while(!closed){var incoming=listener.accept();var outgoing=new java.net.Socket("127.0.0.1",target);sockets.add(incoming);sockets.add(outgoing);
                    Thread.ofVirtual().start(()->copy(incoming,outgoing));Thread.ofVirtual().start(()->copy(outgoing,incoming));}
            }catch(Exception ignored){}});
        }
        static void copy(java.net.Socket from,java.net.Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(Exception ignored){}finally{try{from.close();to.close();}catch(Exception ignored){}}}
        public void close(){closed=true;try{if(listener!=null)listener.close();}catch(Exception ignored){}for(var socket:sockets)try{socket.close();}catch(Exception ignored){}}
    }
    static List<Map<String,Object>> run()throws Exception {
        List<Map<String,Object>> results=new ArrayList<>();
        for(String script:List.of("multi_chunk","bad_json","disconnect","429","timeout","usage_missing","usage_duplicate")){
            AtomicInteger attempts=new AtomicInteger();HttpServer fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fake.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            fake.createContext("/chat/completions",e->{
                e.getRequestBody().readAllBytes();int n=attempts.incrementAndGet();
                try {
                    if(script.equals("timeout")){Thread.sleep(1500);response(e,200,"application/json","{}");return;}
                    if(script.equals("429")){response(e,n==1?429:200,"application/json",n==1?"{\"error\":\"synthetic_rate_limit\"}":"{\"choices\":[{\"message\":{\"content\":\"synthetic\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");return;}
                    if(script.equals("bad_json")){response(e,200,"application/json","{");return;}
                    String sse="data: {\"choices\":[{\"delta\":{\"content\":\"DNS \"}}]}\n\n"+"data: {\"choices\":[{\"delta\":{\"content\":\"合成\"}}]}\n\n";
                    String usage="data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n";
                    if(!script.equals("usage_missing"))sse+=usage;if(script.equals("usage_duplicate"))sse+=usage;
                    if(!script.equals("disconnect"))sse+="data: [DONE]\n\n";
                    if(script.equals("disconnect")){
                        byte[] bytes=sse.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,bytes.length+100);e.getResponseBody().write(bytes);e.getResponseBody().flush();e.close();
                    }else response(e,200,"text/event-stream",sse);
                }catch(Exception ignored){e.close();}
            });fake.start();
            ModelUsageLedger ledger=ModelUsageLedger.create();OpenAiCompatibleChatClient client=new OpenAiCompatibleChatClient(new ChatClientSettings("http://127.0.0.1:"+fake.getAddress().getPort(),"synthetic","qwen3.8-flash",64,1,.001,1,true),new UpstreamGate(20));
            Throwable failure=null;StringBuilder text=new StringBuilder();
            try {
                if(List.of("bad_json","429","timeout").contains(script))client.chat(List.of(new ChatMessage("user","synthetic")),null,ledger,"p0-protocol");
                else {
                    CountDownLatch done=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> fail=new java.util.concurrent.atomic.AtomicReference<>();
                    client.streamChat(List.of(new ChatMessage("user","synthetic")),null,ledger,"p0-protocol").subscribe(new Flow.Subscriber<ModelDelta>(){
                        public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ModelDelta d){text.append(d.text());}public void onError(Throwable t){fail.set(t);done.countDown();}public void onComplete(){done.countDown();}
                    });if(!done.await(10,TimeUnit.SECONDS))throw new IllegalStateException("stream not terminal");failure=fail.get();
                }
            }catch(Throwable t){failure=t;}finally{client.close();fake.stop(0);}
            boolean expectedFailure=List.of("bad_json","disconnect","timeout").contains(script);
            if((failure!=null)!=expectedFailure)throw new IllegalStateException("LLM脚本异常分类不符: "+script+" actual="+(failure==null?"none":failure.getClass().getSimpleName()));
            if(!List.of("bad_json","429","timeout").contains(script)&&!text.toString().equals("DNS 合成"))throw new IllegalStateException("delta content differs");
            if(ledger.entries().size()!=1)throw new IllegalStateException("usage entry count differs");var entry=ledger.entries().getFirst();
            Map<String,Object> row=new LinkedHashMap<>();row.put("case_id","llm-"+script);row.put("status","通过");row.put("attempts",attempts.get());row.put("usage_source",entry.usageSource());row.put("total_tokens",entry.totalTokens());
            row.put("assertion","真实OpenAICompatibleClient脚本化传输；缺失unknown、重复usage不重复计费、已吐delta不重试");results.add(row);
        }
        for(String script:List.of("connect_failure","initialize_failure","submitted_response_lost","finished","running","unknown","restart_clears_ledger")){
            AtomicInteger connects=new AtomicInteger(),submits=new AtomicInteger();HttpServer fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fake.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            fake.createContext("/mcp",e->{try {
                var req=JSON.readTree(e.getRequestBody());String method=req.path("method").asText();Object id=JSON.convertValue(req.path("id"),Object.class);
                if(method.equals("initialize")){
                    int n=connects.incrementAndGet();if(script.equals("initialize_failure")&&n<3){response(e,503,"application/json","{}");return;}
                    response(e,200,"application/json",JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"result",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"serverInfo",Map.of("name","synthetic","version","1")))));
                }else if(method.startsWith("notifications/")){e.sendResponseHeaders(202,-1);e.close();}
                else if(method.equals("tools/call")){
                    submits.incrementAndGet();if(script.equals("submitted_response_lost")){e.sendResponseHeaders(200,1000);e.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));e.close();return;}
                    String state=script.equals("restart_clears_ledger")?"unknown":List.of("finished","running","unknown").contains(script)?script:"finished";
                    String payload=JSON.writeValueAsString(Map.of("ok",true,"status",state,"job_id","p0-job"));
                    response(e,200,"application/json",JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"result",Map.of("content",List.of(Map.of("type","text","text",payload)),"isError",false))));
                }else response(e,404,"application/json","{}");
            }catch(Exception ignored){e.close();}});fake.start();
            DelayedProxy proxy=script.equals("connect_failure")?new DelayedProxy(fake.getAddress().getPort()):null;
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(StreamableHttpHarnessClient.class);
            var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
            var client=new StreamableHttpHarnessClient("http://127.0.0.1:"+(proxy==null?fake.getAddress().getPort():proxy.port)+"/mcp");HarnessReply reply;int transportAttempts;
            try{reply=client.call(script.equals("submitted_response_lost")?"grade_plugin":"get_task_status",Map.of("job_id","p0-job"),null);
                int retries=(int)appender.list.stream().filter(e->e.getFormattedMessage().startsWith("mcp_call_retry")).count();transportAttempts=proxy==null?connects.get():retries+1;
            }finally{logger.detachAppender(appender);appender.stop();if(proxy!=null)proxy.close();fake.stop(0);}
            if(submits.get()!=1)throw new IllegalStateException("MCP重复提交: "+script);
            if(script.equals("submitted_response_lost")&&reply.submissionPhase()!=HarnessReply.SubmissionPhase.UNKNOWN)throw new IllegalStateException("UNKNOWN phase lost");
            if(script.endsWith("failure")&&transportAttempts!=3)throw new IllegalStateException("limited retry changed");
            Map<String,Object> row=new LinkedHashMap<>();row.put("case_id","mcp-"+script);row.put("status","通过");row.put("connect_attempts",transportAttempts);row.put("submitted_calls",submits.get());row.put("assertion","真实HarnessMcpClient有限建连重试；已提交结果未知仅1次，不盲重试");results.add(row);
        }
        return results;
    }
}
