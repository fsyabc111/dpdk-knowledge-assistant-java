package io.dpdk.assistant.platform.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * 迁移后完整 catalog 差分：与冻结基准 expected-catalog-b15.json（源自 P0 合成 oracle 库
 * 的真实 catalog）逐项比较表/列/索引/约束。任何未解释差异都会让迁移失败，
 * 不允许“仅 baseline/validate 成功”当作结构证明（契约 schema-migration.md）。
 *
 * 比对时排除 flyway_schema_history 与 alembic_version（版本表所有权随链切换）。
 */
public final class CatalogVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogVerifier() {
    }

    /** @return 差异列表；空列表表示完全一致。liTableOptional=true 时允许缺 LI 动态表。 */
    public static List<String> verify(DataSource dataSource, boolean liTableOptional) {
        JsonNode expected;
        try (InputStream in = CatalogVerifier.class.getResourceAsStream(
                "/migration/J1-01/expected-catalog-b15.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少基准 fixture /migration/J1-01/expected-catalog-b15.json");
            }
            expected = MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取基准 catalog 失败", e);
        }

        Live live;
        try (Connection c = dataSource.getConnection()) {
            live = snapshot(c);
        } catch (SQLException e) {
            throw new IllegalStateException("catalog 快照失败", e);
        }

        List<String> diffs = new ArrayList<>();

        // 表与列
        Map<String, JsonNode> expectedTables = new TreeMap<>();
        expected.get("tables").forEach(t -> expectedTables.put(t.get("name").asText(), t));
        for (var e : expectedTables.entrySet()) {
            var actualCols = live.tables.get(e.getKey());
            if (actualCols == null) {
                if (liTableOptional && e.getKey().equals(CatalogProbe.LI_TABLE)) {
                    continue;
                }
                diffs.add("缺表: " + e.getKey());
                continue;
            }
            List<String> colExpect = new ArrayList<>();
            e.getValue().get("columns").forEach(col -> colExpect.add(columnLine(col)));
            List<String> colActual = new ArrayList<>();
            actualCols.forEach(col -> colActual.add(columnLine(col)));
            if (!colExpect.equals(colActual)) {
                for (String miss : colExpect) {
                    if (!colActual.contains(miss)) {
                        diffs.add(e.getKey() + " 缺列/属性: " + miss);
                    }
                }
                for (String extra : colActual) {
                    if (!colExpect.contains(extra)) {
                        diffs.add(e.getKey() + " 多列/属性: " + extra);
                    }
                }
            }
        }
        for (String extraTable : live.tables.keySet()) {
            if (!expectedTables.containsKey(extraTable)) {
                diffs.add("多表: " + extraTable);
            }
        }

        // 索引（按 表+名 对齐，比对归一化定义）
        Map<String, String> expectedIndexes = new TreeMap<>();
        expected.get("indexes").forEach(i -> expectedIndexes.put(
                i.get("table").asText() + "|" + i.get("name").asText(), normalizeIndex(i.get("def").asText())));
        for (var e : expectedIndexes.entrySet()) {
            String actual = live.indexes.get(e.getKey());
            if (actual == null) {
                String table = e.getKey().substring(0, e.getKey().indexOf('|'));
                if (liTableOptional && table.equals(CatalogProbe.LI_TABLE)) {
                    continue;
                }
                diffs.add("缺索引: " + e.getKey());
            } else if (!actual.equals(e.getValue())) {
                diffs.add("索引定义不同: " + e.getKey());
            }
        }
        for (String extra : live.indexes.keySet()) {
            if (!expectedIndexes.containsKey(extra)) {
                diffs.add("多索引: " + extra);
            }
        }

        // 约束
        Map<String, String> expectedConstraints = new TreeMap<>();
        expected.get("constraints").forEach(k -> expectedConstraints.put(
                k.get("table").asText() + "|" + k.get("name").asText(),
                k.get("type").asText() + ":" + k.get("def").asText()));
        for (var e : expectedConstraints.entrySet()) {
            String actual = live.constraints.get(e.getKey());
            if (actual == null) {
                diffs.add("缺约束: " + e.getKey());
            } else if (!actual.equals(e.getValue())) {
                diffs.add("约束定义不同: " + e.getKey());
            }
        }
        for (String extra : live.constraints.keySet()) {
            if (!expectedConstraints.containsKey(extra)) {
                diffs.add("多约束: " + extra);
            }
        }
        return diffs;
    }

    private static String columnLine(JsonNode col) {
        String gen = "NEVER".equals(col.path("generated").asText("NEVER")) ? "" :
                "|gen=" + col.path("gen_expr").asText("");
        String def = col.path("default").isNull() ? "" : "|def=" + col.path("default").asText();
        return col.get("name").asText() + "|" + col.get("udt").asText()
                + "|" + col.get("nullable").asText() + def + gen;
    }

    private static String normalizeIndex(String def) {
        return def.replaceAll("\\s+", " ").trim()
                .replaceAll("^CREATE (UNIQUE )?INDEX [^ ]+ ON public\\.", "CREATE $1INDEX ON t.");
    }

    private record Live(Map<String, List<JsonNode>> tables, Map<String, String> indexes,
                        Map<String, String> constraints) {
    }

    private static Live snapshot(Connection c) throws SQLException {
        Map<String, List<JsonNode>> tables = new TreeMap<>();
        try (Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT table_name, json_agg(json_build_object("
                            + "'name', column_name, 'udt', udt_name, 'nullable', is_nullable, "
                            + "'default', column_default, 'generated', is_generated, 'gen_expr', generation_expression) "
                            + "ORDER BY ordinal_position) AS cols "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() "
                            + "AND table_name NOT IN ('flyway_schema_history','alembic_version') "
                            + "GROUP BY table_name");
            while (rs.next()) {
                try {
                    JsonNode cols = MAPPER.readTree(rs.getString(2));
                    List<JsonNode> list = new ArrayList<>();
                    cols.forEach(list::add);
                    tables.put(rs.getString(1), list);
                } catch (Exception e) {
                    throw new SQLException("列快照解析失败: " + rs.getString(1));
                }
            }
        }

        Map<String, String> indexes = new TreeMap<>();
        try (Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT tablename, indexname, indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' "
                            + "AND tablename NOT IN ('flyway_schema_history','alembic_version') "
                            + "AND indexname NOT LIKE 'flyway%'");
            while (rs.next()) {
                indexes.put(rs.getString(1) + "|" + rs.getString(2), normalizeIndex(rs.getString(3)));
            }
        }
        Map<String, String> constraints = new TreeMap<>();
        try (Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT conrelid::regclass::text AS tbl, conname, contype::text, pg_get_constraintdef(oid) "
                            + "FROM pg_constraint WHERE connamespace = 'public'::regnamespace "
                            + "AND conrelid::regclass::text NOT IN ('flyway_schema_history','alembic_version')");
            while (rs.next()) {
                constraints.put(rs.getString(1) + "|" + rs.getString(2),
                        rs.getString(3) + ":" + rs.getString(4));
            }
        }
        return new Live(tables, indexes, constraints);
    }
}
