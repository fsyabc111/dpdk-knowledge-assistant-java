package db.migration;

import io.dpdk.assistant.platform.ids.PythonUuid5;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V0015：讲解任务身份标记（等价 alembic 0015_explain_purpose）。
 *
 * 加列 purpose TEXT NOT NULL DEFAULT 'chat' 后，只对同时满足以下条件的行回填 'explain'：
 * session_id 以 "explain-" 开头、request_id 精确等于 uuid5(URL, "assignment-explain:&lt;submission_id&gt;")
 * 且对应 grading_submissions.user_id 与该行 user_id 一致（合法/伪造身份分别可验证）。
 * 原 input_hash 不做任何改动，也不猜测历史关联。
 */
public class V0015__Explain_purpose extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE rag_requests ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'chat'");
        }
        backfill(conn);
    }

    private void backfill(Connection conn) throws SQLException {
        String candidateSql = "SELECT id, session_id, request_id, user_id FROM rag_requests "
                + "WHERE session_id LIKE 'explain-%' AND purpose = 'chat'";
        String submissionSql = "SELECT user_id FROM grading_submissions WHERE id = ?";
        String updateSql = "UPDATE rag_requests SET purpose = 'explain' WHERE id = ?";

        try (Statement st = conn.createStatement();
             PreparedStatement findSubmission = conn.prepareStatement(submissionSql);
             PreparedStatement update = conn.prepareStatement(updateSql)) {
            ResultSet rows = st.executeQuery(candidateSql);
            while (rows.next()) {
                String id = rows.getString("id");
                String sessionId = rows.getString("session_id");
                String requestId = rows.getString("request_id");
                String userId = rows.getString("user_id");
                if (sessionId == null || sessionId.length() <= "explain-".length()) {
                    continue;
                }
                String submissionId = sessionId.substring("explain-".length());
                String expected = PythonUuid5.uuid5Url("assignment-explain:" + submissionId);
                if (!expected.equals(requestId)) {
                    continue;
                }
                findSubmission.setString(1, submissionId);
                ResultSet submission = findSubmission.executeQuery();
                if (submission.next() && userId != null && userId.equals(submission.getString(1))) {
                    update.setString(1, id);
                    update.executeUpdate();
                }
            }
        }
    }
}
