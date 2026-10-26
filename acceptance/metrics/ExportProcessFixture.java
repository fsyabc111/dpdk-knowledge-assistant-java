import io.dpdk.assistant.platform.observability.ProcessMetricsRegistry;
import io.dpdk.assistant.contracts.observability.ProcessMetrics.UsageSample;
import java.nio.file.*;

/** 只在root释放CPU窗口后运行；显式合成过程端口事件，不冒充业务hook端到端验证。 */
public class ExportProcessFixture {
    public static void main(String[] args) throws Exception {
        var metrics=new ProcessMetricsRegistry(()->1000);
        metrics.llm("llama_index","success",.2);metrics.retrieval(.05);metrics.retrievalStage("rewrite",.01);
        metrics.fallback();metrics.rateLimit("per_minute");metrics.rag("answered","supported",2);
        metrics.validation("answered",1);metrics.tool("grade_plugin",true,.2,1024);metrics.agentTokens(5000);
        metrics.usage(new UsageSample("answer","llm","upstream","synthetic","USD",21,7L));
        metrics.usage(new UsageSample("answer","llm","unknown","synthetic",null,null,null));
        metrics.usage(new UsageSample("answer","llm","cache_hit","synthetic","USD",0,0L));
        metrics.http("GET",200,"/api/health",0,11,.02);
        Path path=Path.of(args[0]);if(Files.exists(path))throw new IllegalArgumentException("no overwrite");
        Files.writeString(path,metrics.renderPrometheus(),StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}
