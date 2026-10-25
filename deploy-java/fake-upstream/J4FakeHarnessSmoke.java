import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
import io.dpdk.assistant.contracts.model.Deadline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
/** 仅对loopback固定替身的现有Java客户端协议smoke；无PG/真实上游。 */
public class J4FakeHarnessSmoke {
 public static void main(String[] args) throws Exception {
  var uri=java.net.URI.create(args[0]);
  if (!"http".equals(uri.getScheme()) || !java.util.Set.of("127.0.0.1","localhost").contains(uri.getHost()))
   throw new IllegalArgumentException("smoke仅允许显式loopback HTTP");
  var client=new StreamableHttpHarnessClient(args[0]); var json=new ObjectMapper();
  var deadline=new Deadline(java.time.Instant.now().plusSeconds(15),System.nanoTime()+15_000_000_000L);
  for(String tool: new String[]{"list_protocols","list_cases","grade_plugin","get_task_status","get_reference_answer"}) {
   var reply=client.call(tool,Map.of("protocol","dns","plugin_source","JAVA_SMOKE_SOURCE_SENTINEL","job_id","java-smoke"),deadline);
   var payload=json.readTree(reply.payloadJson());
   if(reply.error()!=null || !payload.path("ok").asBoolean())throw new AssertionError("tool failed: "+tool);
   if(reply.payloadJson().contains("JAVA_SMOKE_SOURCE_SENTINEL"))throw new AssertionError("source leak");
   if(tool.equals("get_task_status")&&!payload.path("status").asText().equals("finished"))throw new AssertionError("not finished");
  }
  System.out.println("PASS: Java StreamableHttpHarnessClient five-tool MCP loopback smoke; no PG/real upstream");
 }
}
