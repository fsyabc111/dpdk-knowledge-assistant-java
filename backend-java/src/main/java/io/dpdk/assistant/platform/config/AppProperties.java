package io.dpdk.assistant.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 显式数据源与数据库身份期望。全部字段无默认值：缺失即拒绝启动（fail-closed），
 * 不读取 .env / ambient 配置，不回退默认宿主机库。
 */
@ConfigurationProperties(prefix = "dpdk")
public record AppProperties(DataSourceSpec datasource, DatabaseIdentitySpec databaseIdentity) {

    public record DataSourceSpec(String url, String username, String password) {
    }

    /**
     * expectedDataDir：SHOW data_directory 的实际路径必须位于该目录之下，
     * 用于证明连接的是本仓库隔离实例而不是任何宿主机/生产库。
     */
    public record DatabaseIdentitySpec(
            String expectedDatabase,
            String expectedUser,
            String expectedComment,
            String expectedDataDir) {
    }
}
