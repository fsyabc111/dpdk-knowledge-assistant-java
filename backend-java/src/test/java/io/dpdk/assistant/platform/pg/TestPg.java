package io.dpdk.assistant.platform.pg;

import io.dpdk.assistant.platform.config.AppProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * J1-01 独立测试 PG 基座。
 *
 * 环境变量（缺一即由测试类显式跳过，绝不回退默认库）：
 * - DPDK_TEST_PG_URL：形如 jdbc:postgresql://127.0.0.1:55481/postgres（维护库连接）
 * - DPDK_TEST_PG_USER / DPDK_TEST_PG_PASSWORD
 * - DPDK_TEST_PG_DATA_DIR：期望 data_directory 前缀（本仓库 .migration-artifacts/p1/pgdata）
 *
 * 每个测试创建独立数据库（comment=java-migration-p1-test），结束后删除；
 * 任何库创建/删除都只发生在该显式实例上。
 */
public final class TestPg {

    public static final String COMMENT = "java-migration-p1-test";

    private TestPg() {
    }

    public static String url() {
        return System.getenv("DPDK_TEST_PG_URL");
    }

    public static String user() {
        return System.getenv("DPDK_TEST_PG_USER");
    }

    public static String password() {
        return System.getenv("DPDK_TEST_PG_PASSWORD");
    }

    public static boolean envPresent() {
        String u = url();
        return u != null && !u.isBlank() && user() != null && password() != null
                && System.getenv("DPDK_TEST_PG_DATA_DIR") != null;
    }

    /** 连接同一实例上的指定数据库。 */
    public static Connection connect(String database) throws SQLException {
        String base = url();
        int idx = base.lastIndexOf('/');
        return DriverManager.getConnection(base.substring(0, idx + 1) + database, user(), password());
    }

    /** 创建独立测试库；comment 为 null 表示不设置（负例用途）。返回库名。 */
    public static String createDatabase(String tag, String comment) throws SQLException {
        String safeTag = tag.replaceAll("[^a-z0-9_]", "");
        String name = "p1_t_" + safeTag + "_" + Long.toHexString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(url(), user(), password());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
            if (comment != null) {
                st.execute("COMMENT ON DATABASE " + name + " IS '" + comment + "'");
            }
        }
        return name;
    }

    public static void dropDatabase(String name) throws SQLException {
        try (Connection admin = DriverManager.getConnection(url(), user(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    public static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        String base = url();
        int idx = base.lastIndexOf('/');
        ds.setURL(base.substring(0, idx + 1) + database);
        ds.setUser(user());
        ds.setPassword(password());
        return ds;
    }

    public static AppProperties.DatabaseIdentitySpec identity(String database) {
        return new AppProperties.DatabaseIdentitySpec(database, user(), COMMENT,
                System.getenv("DPDK_TEST_PG_DATA_DIR"));
    }
}
