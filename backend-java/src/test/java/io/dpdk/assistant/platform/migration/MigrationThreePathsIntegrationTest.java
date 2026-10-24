package io.dpdk.assistant.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.platform.pg.TestPg;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * J1-01 三路径迁移集成测试（契约 schema-migration.md）：
 * 空库→B0015；合成0013→baseline13+V0014/V0015（含讲解身份回填与伪造反例）；
 * 等价0015→baseline15 空转；重复启动无变化；部分表目标拒绝。
 * 需要显式 DPDK_TEST_PG_* 环境，否则整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class MigrationThreePathsIntegrationTest {

    private final MigrationController controller = new MigrationController();
    private final List<String> createdDbs = new ArrayList<>();

    @AfterEach
    void cleanup() throws SQLException {
        for (String db : createdDbs) {
            TestPg.dropDatabase(db);
        }
        createdDbs.clear();
    }

    private String newDb(String tag) throws SQLException {
        String db = TestPg.createDatabase(tag, TestPg.COMMENT);
        createdDbs.add(db);
        return db;
    }

    @Test
    void emptyDatabaseAppliesBaselineAndCatalogMatches() throws Exception {
        String db = newDb("empty");
        DataSource ds = TestPg.dataSource(db);
        var out = controller.run(ds, TestPg.identity(db), true);
        assertThat(out.path()).isEqualTo(CatalogProbe.Decision.EMPTY);
        assertThat(out.migrationsApplied()).isGreaterThanOrEqualTo(1);
        assertThat(out.catalogVerified()).as("catalog 差分应清零: %s", out.catalogDiffs()).isTrue();
        // 种子活动指针存在且为空串。
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT release_id FROM rag_index_state WHERE name='active'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEmpty();
        }
    }

    @Test
    void repeatRunIsNoopWithCatalogUnchanged() throws Exception {
        String db = newDb("repeat");
        DataSource ds = TestPg.dataSource(db);
        controller.run(ds, TestPg.identity(db), true);
        String before = businessCatalogSnapshot(ds);
        var second = controller.run(ds, TestPg.identity(db), true);
        assertThat(second.path()).isEqualTo(CatalogProbe.Decision.HISTORY);
        assertThat(second.migrationsApplied()).isZero();
        assertThat(second.catalogVerified()).isTrue();
        assertThat(businessCatalogSnapshot(ds)).isEqualTo(before);
    }

    @Test
    void synthetic0013UpgradesViaV14V15WithExplainBackfill() throws Exception {
        String db = newDb("syn13");
        DataSource ds = TestPg.dataSource(db);
        buildSynthetic0013(db);

        var out = controller.run(ds, TestPg.identity(db), true);
        assertThat(out.path()).isEqualTo(CatalogProbe.Decision.AT_0013);
        assertThat(out.migrationsApplied()).isEqualTo(2); // V0014 + V0015
        assertThat(out.catalogVerified()).as("差分: %s", out.catalogDiffs()).isTrue();

        // 讲解身份回填：合法行→explain；伪造 request_id 与跨用户行保持 chat。
        Map<String, String> purposes = queryMap(ds,
                "SELECT id, purpose FROM rag_requests");
        assertThat(purposes.get("rr_valid")).isEqualTo("explain");
        assertThat(purposes.get("rr_forged")).isEqualTo("chat");
        assertThat(purposes.get("rr_wrong_user")).isEqualTo("chat");
        assertThat(purposes.get("rr_plain")).isEqualTo("chat");
        // input_hash 不被猜测或改写。
        Map<String, String> hashes = queryMap(ds, "SELECT id, input_hash FROM rag_requests");
        assertThat(hashes.get("rr_valid")).isEqualTo("hash-keep-1");
        // V0014 列与部分唯一索引就位。
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE indexname = 'idx_grading_task_job_id'");
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void equivalent0015BaseslineWithoutReexecuting() throws Exception {
        String db = newDb("equiv15");
        DataSource ds = TestPg.dataSource(db);
        applyBaselineSql(db);
        String before = businessCatalogSnapshot(ds);

        var out = controller.run(ds, TestPg.identity(db), true);
        assertThat(out.path()).isEqualTo(CatalogProbe.Decision.AT_0015);
        assertThat(out.migrationsApplied()).isZero();
        assertThat(out.catalogVerified()).isTrue();
        assertThat(businessCatalogSnapshot(ds)).isEqualTo(before);

        // 再次运行：常规 HISTORY 路径，无重执行。
        var second = controller.run(ds, TestPg.identity(db), true);
        assertThat(second.path()).isEqualTo(CatalogProbe.Decision.HISTORY);
        assertThat(second.migrationsApplied()).isZero();
    }

    @Test
    void ambiguousPartialSchemaRefusedWithoutWrites() throws Exception {
        String db = newDb("partial");
        DataSource ds = TestPg.dataSource(db);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id TEXT PRIMARY KEY, password_hash TEXT NOT NULL)");
        }
        assertThatThrownBy(() -> controller.run(ds, TestPg.identity(db), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝");
        // 拒绝路径不得写入 Flyway 历史。
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM information_schema.tables WHERE table_name='flyway_schema_history'");
            assertThat(rs.next()).isFalse();
        }
    }

    /** 构造合成 0013：应用 B0015 后剥离 V0014/V0015 增量，并布置讲解回填样本与 alembic 标记。 */
    private void buildSynthetic0013(String db) throws SQLException {
        applyBaselineSql(db);
        try (Connection c = TestPg.connect(db); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE grading_submissions DROP COLUMN task_job_id");
            st.execute("ALTER TABLE rag_requests DROP COLUMN purpose");
            st.execute("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL, "
                    + "CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num))");
            st.execute("INSERT INTO alembic_version(version_num) VALUES ('0013_task_recovery')");
            // 成绩：abc123 属 U20260000001；sub2 属 U20260000002。
            st.execute("INSERT INTO grading_submissions (id, user_id, session_id, protocol, code_hash, "
                    + "score, passed, total, results_json, created_at) VALUES "
                    + "('abc123','U20260000001',NULL,'dpdk_basic','h1',80,4,5,'{}'::jsonb, now())");
            st.execute("INSERT INTO grading_submissions (id, user_id, session_id, protocol, code_hash, "
                    + "score, passed, total, results_json, created_at) VALUES "
                    + "('sub2','U20260000002',NULL,'dpdk_basic','h2',60,3,5,'{}'::jsonb, now())");
            // rr_valid：合法讲解身份（uuid5 向量见 PythonUuid5Test）。
            st.execute("INSERT INTO rag_requests (id, request_id, input_hash, initial_request, user_id, "
                    + "session_id, question, created_at, updated_at) VALUES "
                    + "('rr_valid','7a60c8a2-15f2-5754-b433-74fa39caf270','hash-keep-1',1,'U20260000001',"
                    + "'explain-abc123','q1','2026-01-01T00:00:00+00:00','2026-01-01T00:00:00+00:00')");
            // rr_forged：explain- 前缀但 request_id 与 uuid5 不符。
            st.execute("INSERT INTO rag_requests (id, request_id, input_hash, initial_request, user_id, "
                    + "session_id, question, created_at, updated_at) VALUES "
                    + "('rr_forged','00000000-0000-0000-0000-000000000000',NULL,1,'U20260000001',"
                    + "'explain-forged','q2','2026-01-01T00:00:00+00:00','2026-01-01T00:00:00+00:00')");
            // rr_wrong_user：uuid5 正确（sub2 的讲解身份）但请求用户不是成绩属主。
            st.execute("INSERT INTO rag_requests (id, request_id, input_hash, initial_request, user_id, "
                    + "session_id, question, created_at, updated_at) VALUES "
                    + "('rr_wrong_user','431b6599-ed9d-57ef-b52d-b01f43f856dd',NULL,1,'U20260000001',"
                    + "'explain-sub2','q3','2026-01-01T00:00:00+00:00','2026-01-01T00:00:00+00:00')");
            // 普通聊天行。
            st.execute("INSERT INTO rag_requests (id, request_id, input_hash, initial_request, user_id, "
                    + "session_id, question, created_at, updated_at) VALUES "
                    + "('rr_plain','req-plain',NULL,0,'U20260000001','sess-1','q4',"
                    + "'2026-01-01T00:00:00+00:00','2026-01-01T00:00:00+00:00')");
        }
    }

    private void applyBaselineSql(String db) throws SQLException {
        try (Connection c = TestPg.connect(db)) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/B0015__baseline.sql"));
        }
    }

    private Map<String, String> queryMap(DataSource ds, String sql) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
        }
        return map;
    }

    /** 业务 catalog 快照（表+列+索引计数），用于重复启动“无业务变化”断言。 */
    private String businessCatalogSnapshot(DataSource ds) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT table_name, column_name, udt_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name NOT IN "
                            + "('flyway_schema_history','alembic_version') "
                            + "ORDER BY table_name, ordinal_position");
            while (rs.next()) {
                sb.append(rs.getString(1)).append('.').append(rs.getString(2))
                        .append(':').append(rs.getString(3)).append('\n');
            }
            ResultSet ix = st.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname='public' "
                            + "AND tablename NOT IN ('flyway_schema_history','alembic_version') "
                            + "ORDER BY indexname");
            while (ix.next()) {
                sb.append(ix.getString(1)).append('\n');
            }
        }
        return sb.toString();
    }
}
