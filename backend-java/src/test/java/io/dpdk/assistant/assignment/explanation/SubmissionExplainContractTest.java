package io.dpdk.assistant.assignment.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader.SubmissionRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 独立于数据库的 Python 身份与 context 载荷对照；不代替集成场景。 */
class SubmissionExplainContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void identityMatchesFrozenPythonUuid5() {
        ExplainIdentity identity = ExplainIdentity.forSubmission("grade_abc");
        assertThat(identity.sessionId()).isEqualTo("explain-grade_abc");
        assertThat(identity.requestId()).isEqualTo("ca952def-7b8d-5e7f-b0fd-2504dcc6936d");
        assertThat(identity).isEqualTo(ExplainIdentity.forSubmission("grade_abc"));
        assertThat(identity.requestId()).isNotEqualTo(ExplainIdentity.forSubmission("grade_xyz").requestId());
    }

    @Test
    void forgedPurposePrefixOrRequestDoesNotAcquireExplainIdentity() {
        ExplainIdentity identity = ExplainIdentity.forSubmission("grade_abc");
        assertThat(ExplainIdentity.submissionId(identity.sessionId(), identity.requestId(), "explain"))
                .isEqualTo("grade_abc");
        assertThat(ExplainIdentity.submissionId(identity.sessionId(), identity.requestId(), "chat")).isNull();
        assertThat(ExplainIdentity.submissionId(identity.sessionId(), "random", "explain")).isNull();
        assertThat(ExplainIdentity.submissionId("ordinary", identity.requestId(), "explain")).isNull();
        assertThat(ExplainIdentity.submissionId(null, identity.requestId(), "explain")).isNull();
    }

    @Test
    void contextContainsTrustedGradeFactsButNoSourceOrHash() throws Exception {
        var row = record("{\"source_origin\":\"uploaded\",\"results\":[{\"case\":\"dns-fail\",\"result\":\"FAIL\"}]}");
        var context = ExplainContext.from(row, "作业一");
        assertThat(context.keySet()).containsExactly("submission_id", "assignment_id", "assignment_title",
                "protocol", "score", "passed", "total", "results", "source_origin", "submitted_at");
        assertThat(context).containsEntry("submission_id", "grade_abc")
                .containsEntry("assignment_title", "作业一").containsEntry("score", 80)
                .containsEntry("source_origin", "uploaded")
                .containsEntry("submitted_at", "2026-10-25 00:00:00+00:00");
        assertThat(MAPPER.writeValueAsString(context)).doesNotContain("SOURCE_SENTINEL", "hash-sentinel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"results\":null}", "{\"results\":false}",
            "{\"results\":0}", "{\"results\":\"\"}", "{\"results\":[]}", "{\"results\":{}}"})
    void falsyResultsFallbackToEmptyList(String payload) throws Exception {
        var context = ExplainContext.from(record(payload), null);
        assertThat(context.get("results")).isEqualTo(List.of());
        assertThat(context.get("assignment_title")).isEqualTo("");
        assertThat(context).containsKey("source_origin");
        assertThat(context.get("source_origin")).isNull();
    }

    private static SubmissionRecord record(String payload) throws Exception {
        return new SubmissionRecord("grade_abc", "student", "asg1", null, "dns", "hash-sentinel",
                80, 4, 5, MAPPER.readTree(payload), OffsetDateTime.parse("2026-10-25T00:00:00Z"));
    }
}
