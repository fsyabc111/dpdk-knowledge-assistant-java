import io.dpdk.assistant.Application;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.observability.ProcessMetricsRegistry;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.usage.UsageLedgerRow;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 真实Application/唯一registry/UsageLedger提交hook/HTTP filter正例。
 * 仅独立TestPg合成用量，不直接调用ProcessMetrics；不证明LLM/RAG/tool业务hook。 */
public class ProcessMetricsHttpSmoke {
  static void assertNoUsageChildren(ProcessMetricsRegistry registry) {
    String text=registry.renderPrometheus();
    for(String family:List.of("assistant_upstream_usage_tokens_total","assistant_upstream_usage_cost_micros_total","assistant_upstream_usage_unknown_total"))
      if(text.contains(family+"{"))throw new AssertionError("usage event before actual transaction commit");
  }
  public static void main(String[] args) throws Exception {
    Path output=Path.of(args[0]);
    if(!output.isAbsolute()||Files.exists(output))throw new IllegalArgumentException("new absolute output required");
    String database=TestPg.createDatabase("p5_process_http",TestPg.COMMENT);
    try {
      var ds=TestPg.dataSource(database);
      new MigrationController().run(ds,TestPg.identity(database),true);
      Map<String,Object> p=new LinkedHashMap<>();
      p.put("server.address","127.0.0.1");p.put("server.port","0");p.put("AUTH_MODE","local");
      p.put("dpdk.datasource.url",TestPg.url().substring(0,TestPg.url().lastIndexOf('/')+1)+database);
      p.put("dpdk.datasource.username",TestPg.user());p.put("dpdk.datasource.password",TestPg.password());
      p.put("dpdk.database-identity.expected-database",database);p.put("dpdk.database-identity.expected-user",TestPg.user());
      p.put("dpdk.database-identity.expected-comment",TestPg.COMMENT);p.put("dpdk.database-identity.expected-data-dir",System.getenv("DPDK_TEST_PG_DATA_DIR"));
      p.put("dpdk.migration.run-on-startup","false");p.put("dpdk.task.background-enabled","false");
      p.put("spring.flyway.enabled","false");p.put("spring.sql.init.mode","never");
      p.put("spring.main.banner-mode","off");p.put("logging.level.root","OFF");
      try(var context=new SpringApplicationBuilder(Application.class).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("metrics-smoke-isolation",p))).run()) {
        if(context.getBean(ProcessMetrics.class)!=context.getBean(ProcessMetricsRegistry.class))throw new AssertionError("registry bean must be shared");
        var ledger=context.getBean(UsageLedgerStore.class);
        var rows=List.of(
          new UsageLedgerRow("metrics-known",null,null,null,"llm","answer","synthetic",13,8,21,"upstream",1,12,7,"USD","fixture-v1","2026-10-26T00:00:00+00:00"),
          new UsageLedgerRow("metrics-unknown",null,null,null,"llm","answer","synthetic",null,null,null,"unknown",1,5,null,null,null,"2026-10-26T00:00:00+00:00"),
          new UsageLedgerRow("metrics-cache",null,null,null,"llm","answer","synthetic",0,0,0,"cache_hit",0,0,0,"USD","fixture-v1","2026-10-26T00:00:00+00:00")
        );
        var registry=context.getBean(ProcessMetricsRegistry.class);
        var runner=context.getBean(TransactionRunner.class);
        try {
          runner.required(tx->{ledger.recordRows(rows);assertNoUsageChildren(registry);throw new IllegalStateException("synthetic rollback");});
          throw new AssertionError("rollback did not fail");
        } catch(IllegalStateException expected) {if(!"synthetic rollback".equals(expected.getMessage()))throw expected;}
        assertNoUsageChildren(registry);
        runner.required(tx->{ledger.recordRows(rows);assertNoUsageChildren(registry);return null;});
        if(!registry.renderPrometheus().contains("assistant_upstream_usage_unknown_total{stage=\"answer\",kind=\"llm\"} 1.0"))throw new AssertionError("afterCommit event missing");
        int port=((WebServerApplicationContext)context).getWebServer().getPort();
        var client=HttpClient.newHttpClient();
        var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/health")).GET().build(),HttpResponse.BodyHandlers.discarding());
        if(health.statusCode()!=200)throw new AssertionError("health HTTP status");
        HttpResponse<String> response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/metrics")).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))throw new AssertionError("metrics HTTP status/type");
        Files.createDirectories(output.getParent());
        Files.writeString(output,response.body(),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
        System.out.println("PASS: actual Application shared registry/no precommit or rollback events/ledger afterCommit/health filter; owned PG known21 cost7 unknown1 cache0; HTTP200 text/plain; LLM/RAG/tools not exercised");
      }
    } finally {TestPg.dropDatabase(database);}
  }
}
