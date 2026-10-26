import io.dpdk.assistant.Application;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.pg.DatabaseIdentityGuard;
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

/** 历史持久gauge种子采样器：保留旧证据重现，不再作为source-process-v2正式验收。
 * 当前生产registry必须使用真实过程事件；数据库存量行不等于RAG/LLM/tool事件。
 * 仅独立TestPg合成种子，无模型/MCP/任务后台。 */
public class MetricsHttpSeedSmoke {
  public static void main(String[] args) throws Exception {
    Path output=Path.of(args[0]);
    if(!output.isAbsolute()||Files.exists(output))throw new IllegalArgumentException("new absolute output required");
    String database=TestPg.createDatabase("p5_metrics_http",TestPg.COMMENT);
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
        var ledger=context.getBean(UsageLedgerStore.class);
        ledger.recordRows(List.of(
          new UsageLedgerRow("metrics-known",null,null,null,"llm","answer","synthetic",13,8,21,"upstream",1,12,7,"USD","fixture-v1","2026-10-26T00:00:00+00:00"),
          new UsageLedgerRow("metrics-unknown",null,null,null,"llm","answer","synthetic",null,null,null,"unknown",1,5,null,null,null,"2026-10-26T00:00:00+00:00"),
          new UsageLedgerRow("metrics-cache",null,null,null,"llm","answer","synthetic",0,0,0,"cache_hit",0,0,0,"USD","fixture-v1","2026-10-26T00:00:00+00:00")
        ));
        try(var connection=ds.getConnection()) {
          DatabaseIdentityGuard.verify(connection,TestPg.identity(database));
          try(var statement=connection.prepareStatement("INSERT INTO rag_requests(id,question,status,reason,elapsed_ms,created_at,updated_at) VALUES ('metrics-task','synthetic metrics task','unanswered','no_evidence',1000,'2026-10-26T00:00:00+00:00','2026-10-26T00:00:00+00:00')")) {statement.executeUpdate();}
        }
        int port=((WebServerApplicationContext)context).getWebServer().getPort();
        HttpResponse<String> response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/metrics")).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))throw new AssertionError("metrics HTTP status/type");
        Files.createDirectories(output.getParent());
        Files.writeString(output,response.body(),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
        System.out.println("PASS: actual Application loopback /metrics; owned PG known=21 tokens/7 cost, unknown=1, cache_hit=0-call; HTTP200 text/plain");
      }
    } finally {TestPg.dropDatabase(database);}
  }
}
