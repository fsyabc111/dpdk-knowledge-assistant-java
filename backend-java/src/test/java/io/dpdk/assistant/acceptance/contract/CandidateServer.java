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
        private volatile boolean frozen=false;
        private volatile String caseId="setup";
        private final java.util.List<Map<String,Object>> calls=new java.util.concurrent.CopyOnWriteArrayList<>();
        public void caseId(String value){caseId=value;}
        public java.util.List<Map<String,Object>> calls(){return java.util.List.copyOf(calls);}
        public synchronized void set(Instant value) { base=value; ticks.set(0); last=Instant.EPOCH; }
        public Instant nowUtc() {
            Instant value=frozen?base:base.plusNanos(ticks.getAndIncrement()*1000);
            var stack=StackWalker.getInstance().walk(frames->frames.filter(f->f.getClassName().startsWith("io.dpdk.assistant.")&&!f.getClassName().startsWith("io.dpdk.assistant.acceptance.contract.")).limit(8).map(f->f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber()).toList());
            calls.add(Map.of("case_id",caseId,"value",SystemClockAdapter.pythonIso(value),"thread",Thread.currentThread().getName(),"callers",stack));
            return value;
        }
        public void freeze(Instant value) { set(value);frozen=true; }
        public long monotonicNanos() { return System.nanoTime(); }
        public synchronized String nextSessionIso() {
            Instant current=nowUtc(); if(frozen)return SystemClockAdapter.pythonIso(current); if (!current.isAfter(last)) current=last.plusNanos(1000);
            last=current;return SystemClockAdapter.pythonIso(current);
        }
    }
    /** 对应P0 approved deterministic_id_port：只注入熵源，仍由真实writer派生grade身份。 */
    private static java.util.UUID gradeUuid(long value) {
        char[] letters=new char[8];for(int i=7;i>=0;i--){letters[i]="abcdef".charAt((int)(value%6));value/=6;}
        char[] hex=("abcdefab"+new String(letters)+"abcdefabcdefabcd").toCharArray();hex[12]='4';hex[16]=Character.forDigit((Character.digit(hex[16],16)&3)|8,16);
        String v=new String(hex);return java.util.UUID.fromString(v.substring(0,8)+"-"+v.substring(8,12)+"-"+v.substring(12,16)+"-"+v.substring(16,20)+"-"+v.substring(20));
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
        settings.put("dpdk.model.chat.base-url","http://127.0.0.1:9/v1");settings.put("dpdk.model.chat.api-key","j5-loopback-only");
        settings.put("dpdk.model.chat.model-name","qwen3.8-flash");
        settings.put("dpdk.model.embedding.base-url","http://127.0.0.1:9/v1");settings.put("dpdk.model.embedding.api-key","j5-loopback-only");
        settings.put("dpdk.model.embedding.model-name","text-embedding-v4");
        settings.put("dpdk.harness.url",upstream+"/mcp");
        settings.put("dpdk.maintenance.api-key","p0-synthetic-maintenance");
        FixedClock clock=new FixedClock();
        java.util.concurrent.atomic.AtomicLong gradeIds=new java.util.concurrent.atomic.AtomicLong();
        var boundaryGate=new io.dpdk.assistant.model.UpstreamGate(20);
        var chatBoundary=new io.dpdk.assistant.model.OpenAiCompatibleChatClient(new io.dpdk.assistant.model.ChatClientSettings(upstream+"/v1","synthetic","qwen3.8-flash",2048,3,.5,90,true),boundaryGate);
        var embeddingBoundary=new io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient(new io.dpdk.assistant.model.EmbeddingClientSettings(upstream+"/v1","synthetic","text-embedding-v4",10,5,1,60,()->0d),boundaryGate);
        var ctx=new SpringApplicationBuilder(Application.class).initializers(context->{
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("j5-explicit",settings));
            ((GenericApplicationContext)context).registerBean("j5Clock",ClockPort.class,()->clock,d->d.setPrimary(true));
            ((GenericApplicationContext)context).registerBean("j5GradeUuid",io.dpdk.assistant.contracts.platform.UuidPort.class,()->()->gradeUuid(gradeIds.incrementAndGet()),d->d.setPrimary(true));
            // 仅替换外部客户端传输，Model*Properties/EmbeddingConfig和真正fingerprint仍为冻结逻辑配置。
            ((GenericApplicationContext)context).registerBean("j5ChatBoundary",io.dpdk.assistant.model.OpenAiCompatibleChatClient.class,()->chatBoundary,d->{d.setPrimary(true);d.setDestroyMethodName("close");});
            ((GenericApplicationContext)context).registerBean("j5EmbeddingBoundary",io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient.class,()->embeddingBoundary,d->{d.setPrimary(true);d.setDestroyMethodName("close");});
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
        TransactionCases transactionCases=new TransactionCases(ctx);
        control.createContext("/transaction",exchange->{
            try {String caseName=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(caseName.equals("pg-admission-race"))clock.freeze(Instant.parse("2026-10-24T04:10:00Z"));
                byte[] bytes=JSON.writeValueAsBytes(transactionCases.run(caseName));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){byte[] bytes=JSON.writeValueAsBytes(Map.of("status","失败","error_type",failure.getClass().getSimpleName(),"detail",String.valueOf(failure.getMessage())));exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });
        control.createContext("/auth-vector",exchange->{
            var provider=ctx.getBean(io.dpdk.assistant.auth.AuthSettingsProvider.class);var original=provider.current();
            try {
                var input=JSON.readTree(exchange.getRequestBody());var resolver=ctx.getBean(io.dpdk.assistant.auth.AuthPrincipalResolver.class);
                provider.replace(io.dpdk.assistant.auth.AuthServerSettings.of("local","{}",7,8,4,10000));
                var local=resolver.resolve(new io.dpdk.assistant.contracts.identity.RequestHeaders(Map.of()),new io.dpdk.assistant.contracts.identity.AuthSettings());
                if(!local.subject().equals("local")||!local.roles().equals(java.util.List.of("admin")))throw new IllegalStateException("local vector");
                String fixed="p0-synthetic-static-token-value-0001";
                provider.replace(io.dpdk.assistant.auth.AuthServerSettings.of("token",JSON.writeValueAsString(Map.of(fixed,Map.of("subject","p0-token-viewer","roles",java.util.List.of("viewer"),"daily_quota",5))),7,8,4,10000));
                var token=resolver.resolve(new io.dpdk.assistant.contracts.identity.RequestHeaders(Map.of("Authorization","Bearer "+fixed)),new io.dpdk.assistant.contracts.identity.AuthSettings());
                if(!token.subject().equals("p0-token-viewer")||token.dailyQuota()!=5)throw new IllegalStateException("static vector");
                for(String bad:java.util.List.of("","synthetic-invalid")){boolean rejected=false;try{resolver.resolve(new io.dpdk.assistant.contracts.identity.RequestHeaders(Map.of("Authorization","Bearer "+bad)),new io.dpdk.assistant.contracts.identity.AuthSettings());}catch(io.dpdk.assistant.platform.errors.ApiDetailException expected){rejected=expected.status().value()==401;}if(!rejected)throw new IllegalStateException("invalid token accepted");}
                provider.replace(original);var users=ctx.getBean(io.dpdk.assistant.auth.UserAuthRepository.class);
                if(users.resolveToken(input.path("student_token").asText()).isEmpty())throw new IllegalStateException("student token unresolved");
                var admin=ctx.getBean(io.dpdk.assistant.admin.AdminUserRepository.class);admin.setEnabled("U20270000051",false);
                if(users.resolveToken(input.path("disabled_token").asText()).isPresent())throw new IllegalStateException("disabled revoked token accepted");
                admin.setEnabled("U20270000051",true);
                if(users.resolveToken(input.path("disabled_token").asText()).isPresent())throw new IllegalStateException("reenabled revived old token");
                byte[] bytes=JSON.writeValueAsBytes(Map.of("status","通过","local",true,"static",true,"account_revocation",true));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){byte[] bytes=JSON.writeValueAsBytes(Map.of("status","失败","error_type",failure.getClass().getSimpleName(),"detail",String.valueOf(failure.getMessage())));exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}finally{provider.replace(original);exchange.close();}
        });
        control.createContext("/upstream-vectors",exchange->{
            try {byte[] bytes=JSON.writeValueAsBytes(UpstreamVectors.run());exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}
            catch(Exception failure){byte[] bytes=JSON.writeValueAsBytes(Map.of("status","失败","error_type",failure.getClass().getSimpleName(),"detail",String.valueOf(failure.getMessage())));exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });
        control.createContext("/verify-diagnostic",exchange->{
            try {
                var principal=new io.dpdk.assistant.contracts.identity.Principal("U20270000001",java.util.List.of("leader"),10000,false);
                var verifier=ctx.getBean(io.dpdk.assistant.knowledge.workflow.OriginalQuestionVerifier.class);
                var method=io.dpdk.assistant.chat.GroundedQuestionVerifier.class.getDeclaredMethod("check",String.class,String.class);method.setAccessible(true);
                Object result;try{result=method.invoke(verifier,"合成未解答问题","");}catch(java.lang.reflect.InvocationTargetException wrapped){throw (Exception)wrapped.getCause();}
                byte[] bytes=JSON.writeValueAsBytes(result);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){byte[] bytes=JSON.writeValueAsBytes(Map.of("status","失败","error_type",failure.getClass().getSimpleName(),"detail",String.valueOf(failure.getMessage())));exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });
        control.createContext("/fingerprint-negative",exchange->{
            javax.sql.DataSource ds=ctx.getBean(javax.sql.DataSource.class);String original=null;
            try {
                try(var c=ds.getConnection();var q=c.prepareStatement("SELECT configuration FROM rag_releases WHERE id='p0-synthetic-release-1'")){try(var r=q.executeQuery()){r.next();original=r.getString(1);}}
                try(var c=ds.getConnection();var q=c.prepareStatement("UPDATE rag_releases SET configuration=? WHERE id='p0-synthetic-release-1'")){q.setString(1,original+"-synthetic-mismatch");q.executeUpdate();}
                var retrieval=ctx.getBean(io.dpdk.assistant.retrieval.PgReleaseReader.class);float[] vector=new float[1024];vector[0]=1;
                var result=retrieval.searchPinned(retrieval.pin("p0-synthetic-release-1"),new io.dpdk.assistant.contracts.retrieval.SearchInput("DNS",new io.dpdk.assistant.contracts.model.Vector1024(vector)),6);
                var trace=(io.dpdk.assistant.retrieval.RetrievalTrace)result.trace();
                if(!result.chunks().isEmpty()||trace.blockedReason()==null)throw new IllegalStateException("逻辑指纹变化未fail-closed");
                byte[] bytes=JSON.writeValueAsBytes(Map.of("status","通过","chunks",result.chunks().size(),"blocked_reason",trace.blockedReason()));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){byte[] bytes=JSON.writeValueAsBytes(Map.of("status","失败","error_type",failure.getClass().getSimpleName()));exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}
            finally{if(original!=null)try(var c=ds.getConnection();var q=c.prepareStatement("UPDATE rag_releases SET configuration=? WHERE id='p0-synthetic-release-1'")){q.setString(1,original);q.executeUpdate();}catch(Exception failedRestore){System.err.println("J5_FINGERPRINT_RESTORE_FAILED");}exchange.close();}
        });
        control.createContext("/clock-case",exchange->{try{clock.caseId(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.sendResponseHeaders(204,-1);}finally{exchange.close();}});
        control.createContext("/clock-log",exchange->{try{byte[] bytes=JSON.writeValueAsBytes(clock.calls());exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
        control.createContext("/auth-profile",exchange->{try{
            var payload=JSON.readTree(exchange.getRequestBody());String mode=payload.path("mode").asText();
            if(!java.util.Set.of("local","token","account").contains(mode))throw new IllegalArgumentException("unknown mode");
            String spec=payload.has("tokens")?JSON.writeValueAsString(payload.get("tokens")):"{}";
            ctx.getBean(io.dpdk.assistant.auth.AuthSettingsProvider.class).replace(io.dpdk.assistant.auth.AuthServerSettings.of(mode,spec,7,8,4,10000));
            byte[] bytes=JSON.writeValueAsBytes(Map.of("mode",mode,"status","configured"));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failed){exchange.sendResponseHeaders(400,-1);}finally{exchange.close();}});
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
