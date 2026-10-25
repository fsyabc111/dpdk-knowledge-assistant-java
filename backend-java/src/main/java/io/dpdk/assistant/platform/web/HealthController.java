package io.dpdk.assistant.platform.web;

import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** health只表达存活，ready验证实际PG及当前索引声明/成员/物理节点/配置。 */
@RestController
public class HealthController {
    private final DataSource dataSource;
    private final ObjectProvider<IndexService> index;
    public HealthController(DataSource dataSource, ObjectProvider<IndexService> index) {
        this.dataSource = dataSource;
        this.index = index;
    }
    @GetMapping("/api/health") public Map<String, Object> health() {
        return Map.of("ok", true, "app", "knowledge-assistant");
    }
    @GetMapping("/api/ready") public Map<String, Object> ready() {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var check = workers.submit(() -> {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            IndexService service = index.getIfAvailable();
            if (service == null || !service.health(service.activeRelease()).healthy()) {
                throw new IllegalStateException("index not ready");
            }
            return true;
        });
        try { check.get(5, TimeUnit.SECONDS); return Map.of("ok", true); }
        catch (Exception failed) {
            check.cancel(true);
            if (failed instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            org.slf4j.LoggerFactory.getLogger(HealthController.class).warn("ready_check_failed error_type={}", failed.getClass().getSimpleName());
            throw new ApiDetailException(HttpStatus.SERVICE_UNAVAILABLE, "not ready");
        } finally { workers.shutdownNow(); }
    }
}
