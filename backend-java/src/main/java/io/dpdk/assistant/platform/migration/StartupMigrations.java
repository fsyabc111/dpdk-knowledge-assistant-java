package io.dpdk.assistant.platform.migration;

import io.dpdk.assistant.platform.pg.DatabaseIdentityGuard;
import io.dpdk.assistant.platform.config.AppProperties;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动迁移钩子：在 DataSource 装配（已含身份预检）之后、对外服务之前，
 * 按三路径执行受控迁移。可用 dpdk.migration.run-on-startup=false 关闭（如纯只读场景）。
 */
@Component
public class StartupMigrations implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupMigrations.class);

    private final DataSource dataSource;
    private final AppProperties props;
    private final MigrationController controller;
    private final boolean runOnStartup;

    public StartupMigrations(DataSource dataSource, AppProperties props, MigrationController controller,
                             org.springframework.core.env.Environment env) {
        this.dataSource = dataSource;
        this.props = props;
        this.controller = controller;
        this.runOnStartup = !"false".equalsIgnoreCase(env.getProperty("dpdk.migration.run-on-startup"));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!runOnStartup) {
            log.info("启动迁移已按配置关闭（dpdk.migration.run-on-startup=false）");
            return;
        }
        // 预检在 DataSource 装配已做；控制器内再核一次（迁移是 DDL 写入，双保险）。
        MigrationController.Outcome o = controller.run(dataSource, props.databaseIdentity(), true);
        log.info("启动迁移完成: path={}, applied={}, catalogVerified={}",
                o.path(), o.migrationsApplied(), o.catalogVerified());
    }
}
