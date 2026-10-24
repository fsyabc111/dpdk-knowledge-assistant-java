package io.dpdk.assistant.platform.pg;

import io.dpdk.assistant.platform.config.AppProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库身份预检（契约 schema-migration.md：必须先于任何 Flyway/DDL 触发）。
 *
 * 核对四项：current_database()、current_user、数据库 comment（obj_description）、
 * SHOW data_directory（实际路径必须位于期望目录之下）。任何一项不符即抛出
 * {@link DatabaseIdentityException}，调用方必须放弃对该目标的全部写入。
 */
public final class DatabaseIdentityGuard {

    private DatabaseIdentityGuard() {
    }

    public static void verify(Connection connection, AppProperties.DatabaseIdentitySpec expected) {
        String database;
        String user;
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT current_database(), current_user");
            if (!rs.next()) {
                throw new DatabaseIdentityException("无法读取 current_database()/current_user");
            }
            database = rs.getString(1);
            user = rs.getString(2);
        } catch (SQLException e) {
            throw new DatabaseIdentityException("身份查询失败: " + e.getClass().getSimpleName());
        }

        String comment;
        try (PreparedStatement ps = connection.prepareStatement(
                // 数据库是共享对象：comment 存 pg_shdescription，必须用 shobj_description 读取。
                "SELECT shobj_description(d.oid, 'pg_database') FROM pg_database d "
                        + "WHERE d.datname = current_database()")) {
            ResultSet rs = ps.executeQuery();
            comment = rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new DatabaseIdentityException("comment 查询失败: " + e.getClass().getSimpleName());
        }

        String dataDir;
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SHOW data_directory");
            if (!rs.next()) {
                throw new DatabaseIdentityException("无法读取 data_directory");
            }
            dataDir = rs.getString(1);
        } catch (SQLException e) {
            throw new DatabaseIdentityException("data_directory 查询失败: " + e.getClass().getSimpleName());
        }

        var actual = new DatabaseIdentityException.Actual(database, user, comment, dataDir);
        List<String> mismatches = new ArrayList<>();
        if (!expected.expectedDatabase().equals(database)) {
            mismatches.add("database");
        }
        if (!expected.expectedUser().equals(user)) {
            mismatches.add("user");
        }
        String normalizedComment = comment == null ? "" : comment.trim();
        if (!expected.expectedComment().equals(normalizedComment)) {
            mismatches.add("comment");
        }
        if (!isUnder(dataDir, expected.expectedDataDir())) {
            mismatches.add("data_directory");
        }
        if (!mismatches.isEmpty()) {
            throw new DatabaseIdentityException("数据库身份预检失败(" + String.join(",", mismatches) + "): "
                    + DatabaseIdentityException.summarize(expected, actual));
        }
    }

    /** 实际 data_directory 必须位于期望目录之内（realpath 语义：PG 返回的已是解析路径）。 */
    static boolean isUnder(String actualDir, String expectedDir) {
        if (actualDir == null || expectedDir == null) {
            return false;
        }
        return Path.of(actualDir).normalize().startsWith(Path.of(expectedDir).normalize());
    }
}
