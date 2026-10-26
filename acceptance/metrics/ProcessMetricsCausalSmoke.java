import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.Application;
import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.chat.*;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.platform.pg.*;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.task.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 真Application/PG/公开产品与Agent入口因果验收；只替换loopback模型/embedding，不直接喂指标。 */
public class ProcessMetricsCausalSmoke {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String TOKEN="j5-metrics-causal-synthetic-token-00000001",SUBJECT="metrics",RELEASE="metrics-synthetic-release";
  static final String TEXT="DNS 解析域名，核对字段边界。",ANSWER="DNS 解析域名 [1]。";
  static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  static class Fake implements AutoCloseable {
    final HttpServer server;final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger calls=new AtomicInteger(),embeddings=new AtomicInteger();
    volatile String mode="known";final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    Fake() throws Exception {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
      server.createContext("/v1/embeddings",exchange->{try {
        JSON.readTree(exchange.getRequestBody());embeddings.incrementAndGet();
        var vector=new ArrayList<Integer>(Collections.nCopies(1024,0));vector.set(0,1);
        send(exchange,200,Map.of("data",List.of(Map.of("index",0,"embedding",vector)),"model","synthetic-embedding","usage",Map.of("prompt_tokens",1,"total_tokens",1)));
      }catch(Exception e){exchange.close();}});
      server.createContext("/v1/chat/completions",exchange->{try {
        var body=JSON.readTree(exchange.getRequestBody());calls.incrementAndGet();String current=mode;
        if(current.equals("error")){send(exchange,503,Map.of("error",Map.of("message","synthetic unavailable")));return;}
        if(current.equals("block")){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
        boolean tool=false;for(var message:body.path("messages"))if(message.path("role").asText().equals("tool"))tool=true;
        Map<String,Object> message=(!tool&&body.path("tools").size()>0)
          ?Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","fixed-kb","type","function","function",Map.of("name","kb_search","arguments","{\"query\":\"DNS\"}"))))
          :Map.of("role","assistant","content",ANSWER);
        var reply=new LinkedHashMap<String,Object>();reply.put("id","synthetic");reply.put("model","synthetic-metrics");
        reply.put("choices",List.of(Map.of("index",0,"message",message,"finish_reason",message.containsKey("tool_calls")?"tool_calls":"stop")));
        if(current.equals("known"))reply.put("usage",Map.of("prompt_tokens",1,"completion_tokens",1,"total_tokens",2));
        send(exchange,200,reply);
      }catch(Exception e){exchange.close();}});
      server.start();
    }
    String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void close(){release.countDown();server.stop(0);workers.shutdownNow();}
    static void send(com.sun.net.httpserver.HttpExchange exchange,int status,Object value)throws Exception {
      byte[] bytes=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
  }
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static double metric(String text,String sample,Map<String,String> match){
    double sum=0;for(String line:text.split("\n")){
      if(line.startsWith("#")||!(line.startsWith(sample+"{")||line.startsWith(sample+" ")))continue;
      var labels=new HashMap<String,String>();var regex=java.util.regex.Pattern.compile("([a-zA-Z_]+)=\"([^\"]*)\"").matcher(line);
      while(regex.find())labels.put(regex.group(1),regex.group(2));
      if(labels.entrySet().containsAll(match.entrySet()))sum+=Double.parseDouble(line.substring(line.lastIndexOf(' ')+1));
    }return sum;
  }
  static double metric(String text,String sample){return metric(text,sample,Map.of());}
  static HttpResponse<String> request(String base,String path,Object body)throws Exception {
    var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+TOKEN);
    if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
  static String wire(String base,Path output,String phase)throws Exception {
    var response=request(base,"/metrics",null);require(response.statusCode()==200,"metrics status");
    require(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),"metrics media");
    Files.writeString(output.resolve(phase+".prom"),response.body(),StandardOpenOption.CREATE_NEW);return response.body();
  }
  static void sql(DataSource ds,String database,String sql)throws Exception {
    try(var c=ds.getConnection()){DatabaseIdentityGuard.verify(c,TestPg.identity(database));try(var q=c.createStatement()){q.execute(sql);}}
  }
  static long count(DataSource ds,String database,String sql)throws Exception {
    try(var c=ds.getConnection()){DatabaseIdentityGuard.verify(c,TestPg.identity(database));try(var q=c.createStatement();var rows=q.executeQuery(sql)){rows.next();return rows.getLong(1);}}
  }
  static void settled(org.springframework.context.ConfigurableApplicationContext context)throws Exception {
    long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    while(!context.getBean(ManagedTaskRegistry.class).runningIds().isEmpty()&&System.nanoTime()<end)Thread.sleep(5);
    require(context.getBean(ManagedTaskRegistry.class).runningIds().isEmpty(),"task not settled");
    require(context.getBean(ChatTaskExecutor.class).awaitAccountingReceipts(Duration.ofSeconds(3)),"accounting not acknowledged");
  }
  public static void main(String[] args)throws Exception {
    Path output=Path.of(args[0]);require(output.isAbsolute()&&!Files.exists(output),"new absolute output directory required");
    Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    String database=TestPg.createDatabase("p5_metrics_causal",TestPg.COMMENT);
    var checks=new LinkedHashMap<String,Object>();
    try(var fake=new Fake()){
      var ds=TestPg.dataSource(database);new MigrationController().run(ds,TestPg.identity(database),true);
      var props=new LinkedHashMap<String,Object>();props.put("server.address","127.0.0.1");props.put("server.port",0);
      props.put("AUTH_MODE","token");props.put("API_TOKENS_JSON",JSON.writeValueAsString(Map.of(TOKEN,Map.of("subject",SUBJECT,"roles",List.of("viewer"),"daily_quota",100))));
      props.put("dpdk.datasource.url",TestPg.url().substring(0,TestPg.url().lastIndexOf('/')+1)+database);
      props.put("dpdk.datasource.username",TestPg.user());props.put("dpdk.datasource.password",TestPg.password());
      props.put("dpdk.database-identity.expected-database",database);props.put("dpdk.database-identity.expected-user",TestPg.user());
      props.put("dpdk.database-identity.expected-comment",TestPg.COMMENT);props.put("dpdk.database-identity.expected-data-dir",System.getenv("DPDK_TEST_PG_DATA_DIR"));
      props.put("dpdk.migration.run-on-startup",false);props.put("dpdk.task.background-enabled",false);props.put("spring.flyway.enabled",false);props.put("spring.sql.init.mode","never");
      props.put("dpdk.model.chat.base-url",fake.base()+"/v1");props.put("dpdk.model.chat.api-key","synthetic");props.put("dpdk.model.chat.model-name","synthetic-metrics");props.put("dpdk.model.chat.max-retries",0);
      props.put("dpdk.model.embedding.base-url",fake.base()+"/v1");props.put("dpdk.model.embedding.api-key","synthetic");props.put("dpdk.model.embedding.model-name","synthetic-embedding");props.put("dpdk.model.embedding.max-retries",0);
      props.put("dpdk.harness.url",fake.base()+"/mcp");props.put("dpdk.agent.profile-inject-enabled",false);props.put("dpdk.rate-limit-chat-per-minute",8);
      props.put("dpdk.usage.prices-json","{\"synthetic-metrics\":{\"input_per_1k\":1,\"output_per_1k\":2,\"currency\":\"USD\"}}");props.put("dpdk.usage.price-version","synthetic-not-provider-price");
      try(var context=new SpringApplicationBuilder(Application.class).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("metrics-causal-owned",props))).run()){
        var fingerprint=new IndexConfigurationFingerprint().pythonCompatibleConfiguration(context.getBean(EmbeddingConfig.class));
        var vector=new ArrayList<String>(Collections.nCopies(1024,"0"));vector.set(0,"1");
        sql(ds,database,"INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES ('li_metrics_real_pg','"+TEXT+"','{\"source_path\":\"synthetic/dns.c\",\"title\":\"DNS\",\"section\":\"DNS\"}'::jsonb,'["+String.join(",",vector)+"]'::vector)");
        try(var c=ds.getConnection()){DatabaseIdentityGuard.verify(c,TestPg.identity(database));try(var q=c.prepareStatement("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (?,?,?,1)")){q.setString(1,RELEASE);q.setString(2,fingerprint);q.setString(3,"2026-10-26T00:00:00+00:00");q.executeUpdate();}}
        sql(ds,database,"INSERT INTO rag_release_nodes(release_id,node_id) VALUES ('"+RELEASE+"','li_metrics_real_pg');UPDATE rag_index_state SET release_id='"+RELEASE+"' WHERE name='active'");
        String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
        String initial=wire(base,output,"00-initial");
        Map<String,Object> normal=Map.of("message","DNS 做什么","request_id",UUID.randomUUID().toString());
        var response=request(base,"/api/chat",normal);require(response.statusCode()==200,"normal chat HTTP");var result=JSON.readTree(response.body());
        require(result.path("answer_status").asText().equals("answered")&&result.path("citations").size()==1,"normal PG evidence answer");settled(context);
        String known=wire(base,output,"01-known");
        require(metric(known,"assistant_llm_requests_total",Map.of("engine","unknown","result","success"))==1,"product unknown success");
        require(metric(known,"assistant_agent_request_tokens_count")==0,"product must not emit standalone Agent tokens");
        require(metric(known,"assistant_upstream_usage_tokens_total")==fake.calls.get()*2+fake.embeddings.get(),"known returned upstream token amounts");
        require(metric(known,"assistant_upstream_usage_cost_micros_total")==fake.calls.get()*3000,"synthetic configured cost amount");
        checks.put("normal",Map.of("http",200,"answered",true,"actual_PG_citations",1,"product_engine","unknown","upstream_returned_tokens",metric(known,"assistant_upstream_usage_tokens_total"),"synthetic_cost_micros",metric(known,"assistant_upstream_usage_cost_micros_total"),"product_agent_token_samples",0));
        sql(ds,database,"UPDATE rag_index_state SET release_id='' WHERE name='active'");fake.mode="unknown";
        response=request(base,"/api/chat",Map.of("message","DNS fallback","request_id",UUID.randomUUID().toString()));require(response.statusCode()==200,"fallback chat HTTP");settled(context);
        String fallback=wire(base,output,"02-no-release");
        require(metric(fallback,"assistant_chat_fallbacks_total")==1,"real no-active-release fallback missing");
        require(metric(fallback,"assistant_llm_requests_total",Map.of("engine","unknown","result","fallback"))==1,"LLM fallback result");
        require(metric(fallback,"assistant_upstream_usage_unknown_total")>0,"actual missing upstream usage not counted");
        require(JSON.readTree(response.body()).path("citations").size()==0,"no active release must not invent chunks");
        checks.put("fallback",Map.of("no_active_release",true,"citations",0,"fallback_delta",1,"actual_missing_usage_positive",true));
        sql(ds,database,"UPDATE rag_index_state SET release_id='"+RELEASE+"' WHERE name='active'");fake.mode="error";
        response=request(base,"/api/chat",Map.of("message","DNS error","request_id",UUID.randomUUID().toString()));require(response.statusCode()==200,"error terminal HTTP");settled(context);
        String failure=wire(base,output,"03-generation-error");
        require(metric(failure,"assistant_llm_requests_total",Map.of("engine","unknown","result","error"))==1,"generation error must not be success");
        require(metric(failure,"assistant_llm_requests_total",Map.of("result","success"))==1,"error increased success");checks.put("generation_error",Map.of("synthetic_upstream_status",503,"error_delta",1,"success_delta",0));
        fake.mode="block";
        var start=context.getBean(ProductChatService.class).start(new Principal(SUBJECT,List.of("viewer"),100,false),new ChatInput(null,UUID.randomUUID().toString(),"DNS cancelled",Map.of(),null));
        require(fake.entered.await(3,TimeUnit.SECONDS),"blocking model not entered");
        response=request(base,"/api/tasks/"+start.taskId()+"/cancel",Map.of());require(response.statusCode()==200,"cancel endpoint");fake.release.countDown();settled(context);
        String cancelled=wire(base,output,"04-cancelled");
        require(metric(cancelled,"assistant_llm_requests_total",Map.of("engine","unknown","result","cancelled"))==1,"cancelled generation classification");
        require(metric(cancelled,"assistant_llm_requests_total",Map.of("result","success"))==1,"cancel increased success");
        require(metric(cancelled,"assistant_rag_duration_seconds_count")==metric(failure,"assistant_rag_duration_seconds_count"),"cancel invented finish RAG event");
        checks.put("cancellation",Map.of("actual_public_start_and_cancel",true,"cancelled_delta",1,"success_delta",0,"finish_RAG_delta",0));
        int beforeReplay=fake.calls.get(),replays=0;long quota=count(ds,database,"SELECT coalesce(sum(chat_count),0) FROM usage_counters WHERE subject='metrics'");
        long messages=count(ds,database,"SELECT count(*) FROM assistant_messages");
        boolean limited=false;for(int i=0;i<12;i++){
          response=request(base,"/api/chat",normal);if(response.statusCode()==429){limited=true;break;}
          require(response.statusCode()==200&&JSON.readTree(response.body()).path("task_id").asText().equals(result.path("task_id").asText()),"terminal replay identity");replays++;
        }
        require(limited&&replays>0,"per-minute rate limit not exercised");
        require(fake.calls.get()==beforeReplay,"replay or rate limit repeated model");
        require(count(ds,database,"SELECT coalesce(sum(chat_count),0) FROM usage_counters WHERE subject='metrics'")==quota,"replay repeated quota debit");
        require(count(ds,database,"SELECT count(*) FROM assistant_messages")==messages,"replay repeated messages");
        String rate=wire(base,output,"05-rate-limit");require(metric(rate,"assistant_rate_limit_hits_total",Map.of("kind","per_minute"))==1,"real rate-limit hook");
        checks.put("terminal_replay_rate",Map.of("replays",replays,"http",429,"new_model_calls",0,"quota_delta",0,"message_delta",0));
        fake.mode="known";
        var reader=context.getBean(PgReleaseReader.class);
        var tools=AgentToolContext.builder().userId("anonymous").retriever((query,limit,conversation)->{
          var found=reader.searchPinned(reader.pin(RELEASE),new SearchInput(query,null,conversation),limit);
          var trace=(RetrievalTrace)found.trace();return trace.nodes().stream().map(n->new KbChunk(n.id(),n.sourcePath(),n.section(),n.title(),n.content(),n.score())).toList();
        }).build();tools.metrics=context.getBean(ProcessMetrics.class);
        var agent=new AgentOrchestrator(context.getBean(AgentChatModel.class),context.getBean(AgentSettings.class));
        var agentResult=agent.run("anonymous",List.of(),null,"DNS",null,null,null,null,null,tools);
        require(agentResult.tokensUsed==4&&!agentResult.collectedChunks.isEmpty(),"actual standalone facade tokens/PG search");
        String finalWire=wire(base,output,"06-final");require(metric(finalWire,"assistant_agent_request_tokens_count")==1&&metric(finalWire,"assistant_agent_request_tokens_sum")==4,"standalone Agent histogram");
        checks.put("standalone_Agent",Map.of("public_facade","AgentOrchestrator.run","actual_tokens",4,"histogram_count",1,"actual_PG_chunks",agentResult.collectedChunks.size()));
        checks.put("production_source",args[1]);checks.put("all_metrics_fed_only_by_business_events",true);checks.put("real_upstreams",false);checks.put("cost_domain","synthetic configured price, not provider price");
        Files.writeString(output.resolve("causal-checks.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checks),StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: actual Application/owned PG/loopback fake, normal/fallback/error/cancel/replay429/standalone Agent causal events; not live quality/capacity/deployment");
      }
    }finally{TestPg.dropDatabase(database);}
  }
}
