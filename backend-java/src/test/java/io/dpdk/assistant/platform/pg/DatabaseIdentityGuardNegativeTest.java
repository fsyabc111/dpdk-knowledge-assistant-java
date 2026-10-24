package io.dpdk.assistant.platform.pg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.platform.config.AppProperties;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 身份预检负例：错库/错角色/缺 comment/错 data_directory 都必须拒绝。
 * 需要显式 DPDK_TEST_PG_* 环境；未设置时整类跳过（不允许静默指向其他库）。
 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class DatabaseIdentityGuardNegativeTest {

    private static final String DATA_DIR = System.getenv("DPDK_TEST_PG_DATA_DIR");

    private AppProperties.DatabaseIdentitySpec spec(String db, String user, String comment, String dir) {
        return new AppProperties.DatabaseIdentitySpec(db, user, comment, dir);
    }

    @Test
    void wrongDatabaseRejected() throws Exception {
        try (Connection c = TestPg.connect(TestPg.createDatabase("guard_wrongdb", TestPg.COMMENT))) {
            assertThatThrownBy(() -> DatabaseIdentityGuard.verify(c,
                    spec("some_other_db", TestPg.user(), TestPg.COMMENT, DATA_DIR)))
                    .isInstanceOf(DatabaseIdentityException.class)
                    .hasMessageContaining("database");
        }
    }

    @Test
    void wrongUserRejected() throws Exception {
        try (Connection c = TestPg.connect(TestPg.createDatabase("guard_wronguser", TestPg.COMMENT))) {
            assertThatThrownBy(() -> DatabaseIdentityGuard.verify(c,
                    spec(currentDb(c), "not_the_role", TestPg.COMMENT, DATA_DIR)))
                    .isInstanceOf(DatabaseIdentityException.class)
                    .hasMessageContaining("user");
        }
    }

    @Test
    void missingCommentRejected() throws Exception {
        // 无 comment 的目标库：形似“误建的普通库”，必须拒绝。
        String db = TestPg.createDatabase("guard_nocomment", null);
        try (Connection c = TestPg.connect(db)) {
            assertThatThrownBy(() -> DatabaseIdentityGuard.verify(c,
                    spec(currentDb(c), TestPg.user(), TestPg.COMMENT, DATA_DIR)))
                    .isInstanceOf(DatabaseIdentityException.class)
                    .hasMessageContaining("comment");
        }
    }

    @Test
    void wrongDataDirectoryRejected() throws Exception {
        try (Connection c = TestPg.connect(TestPg.createDatabase("guard_wrongdir", TestPg.COMMENT))) {
            assertThatThrownBy(() -> DatabaseIdentityGuard.verify(c,
                    spec(currentDb(c), TestPg.user(), TestPg.COMMENT, "/definitely/not/our/pgdata")))
                    .isInstanceOf(DatabaseIdentityException.class)
                    .hasMessageContaining("data_directory");
        }
    }

    @Test
    void correctIdentityPasses() throws Exception {
        String db = TestPg.createDatabase("guard_ok", TestPg.COMMENT);
        try (Connection c = TestPg.connect(db)) {
            DatabaseIdentityGuard.verify(c, spec(currentDb(c), TestPg.user(), TestPg.COMMENT, DATA_DIR));
        }
    }

    private static String currentDb(Connection c) throws SQLException {
        var rs = c.createStatement().executeQuery("SELECT current_database()");
        rs.next();
        return rs.getString(1);
    }
}
