package io.dpdk.assistant.platform.ids;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** uuid5 对照向量由系统 Python（uuid.uuid5(uuid.NAMESPACE_URL, ...)）生成。 */
class PythonUuid5Test {

    @Test
    void matchesPythonVectors() {
        assertThat(PythonUuid5.uuid5Url("assignment-explain:abc123"))
                .isEqualTo("7a60c8a2-15f2-5754-b433-74fa39caf270");
        assertThat(PythonUuid5.uuid5Url(
                "assignment-explain:00000000-0000-0000-0000-000000000001"))
                .isEqualTo("ae8616d5-85a1-51cb-b33a-7171a57c1e91");
        assertThat(PythonUuid5.uuid5Url("assignment-explain:explain-demo"))
                .isEqualTo("5b3cb620-bbf7-5e69-bccc-dcacd8209c41");
        assertThat(PythonUuid5.uuid5Url("assignment-explain:plain"))
                .isEqualTo("49716172-e0f8-5266-9b0e-a01299a1747a");
    }
}
