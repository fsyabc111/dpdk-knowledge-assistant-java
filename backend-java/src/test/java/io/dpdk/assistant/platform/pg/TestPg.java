package io.dpdk.assistant.platform.pg;

import io.dpdk.assistant.platform.config.AppProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * - DPDK_TEST_PG_DATA_DIR：期望 data_directory（本仓库 .migration-artifacts/p1/pgdata）
 *
 * 每个测试创建独立数据库（comment=java-migration-p1-test），结束后删除；
 * 任何库创建/删除都只发生在该显式实例上。
 *
 * ST-01（2026-10-25 验收修复）：createDatabase/dropDatabase 在任何写操作之前先做
 * 实例身份预检——维护连接上核对 current_user 逐字相等、SHOW data_directory 位于
 * 期望目录之内；配置不完整、身份不符或连接失败一律抛出且零写入。测试辅助入口
 * 不再依赖应用侧迁移预检兜底。
 */
public final class TestPg {

    public static final String COMMENT = "java-migration-p1-test";

    /** 负例注入点（仅身份预检负例测试设置；保持 null 即读真实环境变量）。 */
    static volatile String overrideUrl;
    static volatile String overrideUser;
    static volatile String overridePassword;
    static volatile String overrideDataDir;

    private TestPg() {
    }

    public static String url() {
        String v = overrideUrl;
        return v != null ? v : System.getenv("DPDK_TEST_PG_URL");
    }

    public static String user() {
        String v = overrideUser;
        return v != null ? v : System.getenv("DPDK_TEST_PG_USER");
    }

    public static String password() {
        String v = overridePassword;
        return v != null ? v : System.getenv("DPDK_TEST_PG_PASSWORD");
    }

    static String dataDir() {
        String v = overrideDataDir;
        return v != null ? v : System.getenv("DPDK_TEST_PG_DATA_DIR");
    }

    public static boolean envPresent() {
        String u = url();
        return u != null && !u.isBlank() && user() != null && password() != null
                && dataDir() != null;
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
        requireInstanceIdentity();
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
        requireInstanceIdentity();
        try (Connection admin = DriverManager.getConnection(url(), user(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    /**
     * ST-01 实例身份预检：零写入。核对当前连接角色与期望角色（环境 USER）逐字相等、
     * 实际 data_directory 位于期望目录（环境 DATA_DIR）之内（复用 DatabaseIdentityGuard
     * 的 isUnder 路径语义）。任何一项不符、配置缺失或连接失败都抛出，调用方不得写库。
     */
    static void requireInstanceIdentity() throws SQLException {
        String u = url();
        String usr = user();
        String dir = dataDir();
        if (u == null || u.isBlank() || usr == null || usr.isBlank()
                || dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "测试PG配置不完整（DPDK_TEST_PG_URL/USER/DATA_DIR），拒绝建库/删库");
        }
        try (Connection admin = DriverManager.getConnection(u, usr, password());
             Statement st = admin.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT current_user");
            rs.next();
            String actualUser = rs.getString(1);
            if (!usr.equals(actualUser)) {
                throw new IllegalStateException("测试PG身份预检失败：连接角色 " + actualUser
                        + " 与期望 " + usr + " 不符，拒绝建库/删库");
            }
            rs = st.executeQuery("SHOW data_directory");
            rs.next();
            String actualDir = rs.getString(1);
            if (!DatabaseIdentityGuard.isUnder(actualDir, dir)) {
                throw new IllegalStateException("测试PG身份预检失败：data_directory " + actualDir
                        + " 不在期望目录 " + dir + " 之下，拒绝建库/删库");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("测试PG身份预检连接失败（零写入退出）："
                    + e.getMessage(), e);
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
        return new AppProperties.DatabaseIdentitySpec(database, user(), COMMENT, dataDir());
    }
}
