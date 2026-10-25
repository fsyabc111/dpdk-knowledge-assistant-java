package io.dpdk.assistant.acceptance.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.Application;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.task.ManagedTaskRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** J5候选真HTTP服务；控制端口只绑loopback，不替换任何业务/权限/事务/检索Bean。 */
public final class CandidateServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final class FixedClock implements ClockPort {
        private volatile Instant base = Instant.parse("2026-10-24T04:00:00Z");
        private final AtomicLong ticks = new AtomicLong();
        private Instant last = Instant.EPOCH;
        public synchronized void set(Instant value) { base=value; ticks.set(0); last=Instant.EPOCH; }
        public Instant nowUtc() { return base.plusNanos(ticks.getAndIncrement()*1000); }
        public long monotonicNanos() { return System.nanoTime(); }
        public synchronized String nextSessionIso() {
            Instant current=nowUtc(); if (!current.isAfter(last)) current=last.plusNanos(1000);
            last=current;return SystemClockAdapter.pythonIso(current);
        }
    }
    private static String required(String key) {
        String value=System.getenv(key);
        if(value==null||value.isBlank()) throw new IllegalStateException("缺少显式环境: "+key);
        return value;
    }
    public static void main(String[] args) throws Exception {
        String name=required("J5_DATABASE_NAME");
        if(!name.matches("(?:p5_contract_|p5_ui_|p1_t_p5_)[a-z0-9_]+")) throw new IllegalArgumentException("拒绝非J5候选/UI库");
        String upstream=required("J5_UPSTREAM_BASE");
        java.net.URI u=java.net.URI.create(upstream);
        if(!java.util.Set.of("127.0.0.1","localhost","::1").contains(u.getHost())) throw new IllegalArgumentException("上游必须loopback替身");
        Map<String,Object> settings=new LinkedHashMap<>();
        settings.put("server.address","127.0.0.1");settings.put("server.port",System.getenv().getOrDefault("J5_HTTP_PORT","0"));
        settings.put("AUTH_MODE",System.getenv().getOrDefault("J5_AUTH_MODE","account"));
        settings.put("LLM_DAILY_QUOTA_DEFAULT",10000);
        settings.put("BCRYPT_ROUNDS",4);
        settings.put("dpdk.datasource.url",required("J5_DATABASE_URL"));
        settings.put("dpdk.datasource.username",required("DPDK_TEST_PG_USER"));
        settings.put("dpdk.datasource.password",required("DPDK_TEST_PG_PASSWORD"));
        settings.put("dpdk.database-identity.expected-database",name);
        settings.put("dpdk.database-identity.expected-user",required("DPDK_TEST_PG_USER"));
        settings.put("dpdk.database-identity.expected-comment",required("J5_DATABASE_COMMENT"));
        settings.put("dpdk.database-identity.expected-data-dir",required("DPDK_TEST_PG_DATA_DIR"));
        settings.put("dpdk.task.background-enabled",false);
        settings.put("dpdk.model.chat.base-url",upstream+"/v1");settings.put("dpdk.model.chat.api-key","j5-loopback-only");
        settings.put("dpdk.model.chat.model-name","synthetic-model");
        settings.put("dpdk.model.embedding.base-url",upstream+"/v1");settings.put("dpdk.model.embedding.api-key","j5-loopback-only");
        settings.put("dpdk.model.embedding.model-name","synthetic-embedding");
        settings.put("dpdk.harness.url",upstream+"/mcp");
        settings.put("MAINTENANCE_KEY","p0-synthetic-maintenance");
        FixedClock clock=new FixedClock();
        var ctx=new SpringApplicationBuilder(Application.class).initializers(context->{
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("j5-explicit",settings));
            ((GenericApplicationContext)context).registerBean("j5Clock",ClockPort.class,()->clock,d->d.setPrimary(true));
        }).run();
        HttpServer control=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("J5_CONTROL_PORT","0"))),0);
        control.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        control.createContext("/state",exchange->{
            try {
                String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if("POST".equals(exchange.getRequestMethod())) clock.set(Instant.parse(body));
                EmbeddingConfig cfg=ctx.getBean(EmbeddingConfig.class);
                byte[] bytes=JSON.writeValueAsBytes(Map.of("http_port",((WebServerApplicationContext)ctx).getWebServer().getPort(),
                    "configuration",new IndexConfigurationFingerprint().pythonCompatibleConfiguration(cfg),
                    "running",ctx.getBean(ManagedTaskRegistry.class).runningIds()));
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception e) { byte[] bytes=e.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes); }
            finally {exchange.close();}
        });
        control.createContext("/settled",exchange->{
            long end=System.nanoTime()+30_000_000_000L;
            try {while(!ctx.getBean(ManagedTaskRegistry.class).runningIds().isEmpty()&&System.nanoTime()<end)Thread.sleep(5);
                boolean settled=ctx.getBean(ManagedTaskRegistry.class).runningIds().isEmpty();
                byte[] bytes=JSON.writeValueAsBytes(Map.of("settled",settled));exchange.sendResponseHeaders(settled?200:503,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });
        control.createContext("/semantic",exchange->{
            try {
                java.util.List<Map<String,String>> redaction=new java.util.ArrayList<>(),hashes=new java.util.ArrayList<>();
                for(String value:java.util.List.of("grade_abcdefab12345678","grade_abcdefabaaaa4aab"))
                    redaction.add(Map.of("input",value,"actual",io.dpdk.assistant.agent.fallback.Redact.redact(value)));
                for(String value:java.util.List.of("  cafe\u0301\n","café","a b","a  b","AB","ab"))
                    hashes.add(Map.of("input",value,"actual",io.dpdk.assistant.task.TaskInputHash.compute(value)));
                byte[] bytes=JSON.writeValueAsBytes(Map.of("redaction",redaction,"input_hash",hashes));
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });
        control.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{control.stop(0);ctx.close();}));
        Map<String,Object> ready=Map.of("base_url","http://127.0.0.1:"+((WebServerApplicationContext)ctx).getWebServer().getPort(),
            "control_url","http://127.0.0.1:"+control.getAddress().getPort(),"database",name);
        Path output=Path.of(required("J5_SERVER_STATE"));Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,JSON.writeValueAsString(ready));
        output.toFile().setReadable(false,false);output.toFile().setWritable(false,false);output.toFile().setReadable(true,true);output.toFile().setWritable(true,true);
        System.out.println("J5_CANDIDATE_READY "+JSON.writeValueAsString(ready));
        new CountDownLatch(1).await();
    }
}
