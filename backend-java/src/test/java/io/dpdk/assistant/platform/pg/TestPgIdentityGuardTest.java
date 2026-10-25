package io.dpdk.assistant.platform.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ST-01 修复验收（2026-10-25 U12）：TestPg 建库/删库前的实例身份预检负例。
 *
 * 错 data-dir / 错角色 / 错端口都必须在首个写操作前拒绝，并以 pg_database 计数
 * 证明零写入；dropDatabase 同样受预检保护（错配时目标库不得被删除）。
 * 正例控制库一律 finally 清理，不留 guard 库。未设 DPDK_TEST_PG_URL 整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TestPgIdentityGuardTest {

    private static final String WRONG_DIR = "/deliberately-wrong-u12-st01-identity";

    @AfterEach
    void clearOverrides() {
        TestPg.overrideUrl = null;
        TestPg.overrideUser = null;
        TestPg.overridePassword = null;
        TestPg.overrideDataDir = null;
    }

    /** 测试库计数（p1_t_ 前缀），作为零写入证明的基线。 */
    private static int p1DbCount() throws SQLException {
        try (Connection c = TestPg.connect("postgres");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname LIKE 'p1_t_%'")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean dbExists(String name) throws SQLException {
        try (Connection c = TestPg.connect("postgres");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    @Test
    void wrongDataDirectoryZeroWrites() throws SQLException {
        int before = p1DbCount();
        TestPg.overrideDataDir = WRONG_DIR;
        assertThatThrownBy(() -> TestPg.createDatabase("st01_wrongdir", TestPg.COMMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data_directory");
        TestPg.overrideDataDir = null;
        assertThat(p1DbCount()).isEqualTo(before);
    }

    @Test
    void wrongUserZeroWrites() throws SQLException {
        int before = p1DbCount();
        TestPg.overrideUser = "p1_java_absent_role_u12";
        assertThatThrownBy(() -> TestPg.createDatabase("st01_wronguser", TestPg.COMMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份预检");
        TestPg.overrideUser = null;
        assertThat(p1DbCount()).isEqualTo(before);
    }

    @Test
    void wrongPortZeroWrites() throws SQLException {
        int before = p1DbCount();
        TestPg.overrideUrl = TestPg.url().replaceFirst(":\\d+", ":55499");
        assertThatThrownBy(() -> TestPg.createDatabase("st01_wrongport", TestPg.COMMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连接失败");
        TestPg.overrideUrl = null;
        assertThat(p1DbCount()).isEqualTo(before);
    }

    @Test
    void wrongHostZeroWrites() throws SQLException {
        int before = p1DbCount();
        TestPg.overrideUrl = TestPg.url().replaceFirst("//[^:/]+", "//dpdk-u12-no-such-host.invalid");
        assertThatThrownBy(() -> TestPg.createDatabase("st01_wronghost", TestPg.COMMENT))
                .isInstanceOf(IllegalStateException.class);
        TestPg.overrideUrl = null;
        assertThat(p1DbCount()).isEqualTo(before);
    }

    @Test
    void dropAlsoRequiresIdentityAndKeepsTarget() throws SQLException {
        String db = TestPg.createDatabase("st01_dropctl", TestPg.COMMENT);
        try {
            assertThat(dbExists(db)).isTrue();
            TestPg.overrideDataDir = WRONG_DIR;
            assertThatThrownBy(() -> TestPg.dropDatabase(db))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("data_directory");
            assertThat(dbExists(db)).as("错配预检必须阻止目标库被删除").isTrue();
        } finally {
            TestPg.overrideDataDir = null;
            TestPg.dropDatabase(db);
        }
        assertThat(dbExists(db)).isFalse();
    }

    @Test
    void hostileTagYieldsSafeIdentifierOnly() throws SQLException {
        String db = TestPg.createDatabase("x'); DROP DATABASE p1_guard;--", TestPg.COMMENT);
        try {
            assertThat(db).matches("p1_t_[a-z0-9_]+_[0-9a-f]+");
        } finally {
            TestPg.dropDatabase(db);
        }
    }

    @Test
    void createDropLifecycleSetsAndRemovesComment() throws SQLException {
        String db = TestPg.createDatabase("st01_lifecycle", TestPg.COMMENT);
        try (Connection c = TestPg.connect(db)) {
            DatabaseIdentityGuard.verify(c, TestPg.identity(db));
        } finally {
            TestPg.dropDatabase(db);
        }
        assertThat(dbExists(db)).isFalse();
    }

    @Test
    void incompleteConfigRefusesWithoutConnecting() throws SQLException {
        int before = p1DbCount();
        TestPg.overrideDataDir = "";
        assertThatThrownBy(() -> TestPg.createDatabase("st01_missing", TestPg.COMMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置不完整");
        TestPg.overrideDataDir = null;
        assertThat(p1DbCount()).isEqualTo(before);
    }
}
