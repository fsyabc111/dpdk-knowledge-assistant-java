package io.dpdk.assistant.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import io.dpdk.assistant.platform.pg.DatabaseIdentityGuard;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源装配：先做 fail-closed 配置校验与数据库身份预检，预检通过才交付 DataSource。
 * 因此应用内任何后续使用者（含启动迁移）都不可能先于预检触到数据库。
 */
@Configuration
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(AppProperties props,
            @org.springframework.beans.factory.annotation.Value("${dpdk.datasource.maximum-pool-size:${DPDK_JAVA_DB_MAX_POOL_SIZE:8}}") int maximumPoolSize) {
        ConfigValidator.validate(props);
        if (maximumPoolSize < 1) { throw new IllegalArgumentException("数据库连接池上限必须为正数"); }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.datasource().url());
        ds.setUsername(props.datasource().username());
        ds.setPassword(props.datasource().password());
        ds.setMaximumPoolSize(maximumPoolSize);
        ds.setPoolName("dpdk-java-main");
        try (Connection c = ds.getConnection()) {
            DatabaseIdentityGuard.verify(c, props.databaseIdentity());
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接目标数据库（拒绝继续，fail-closed）: "
                    + e.getClass().getSimpleName());
        }
        return ds;
    }
}
