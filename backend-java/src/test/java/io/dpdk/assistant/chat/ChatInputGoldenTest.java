package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import io.dpdk.assistant.platform.errors.ApiDetailException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import java.util.*;

class ChatInputGoldenTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pydanticRequestVectorsMatchFieldTypesOrderingValuesAndNulls() throws Exception {
        var cases =
                JSON.readTree(
                        getClass().getResourceAsStream("/migration/J3-03/request-golden.json"));
        for (var c : cases) {
            JsonNode actual;
            try {
                ChatInput input = ChatRequestParser.validate(c.path("body"), null);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("session_id", input.sessionId());
                value.put("request_id", input.requestId());
                value.put("message", input.message());
                value.put("context", input.context());
                actual = JSON.valueToTree(Map.of("status", 200, "input", value));
            } catch (ApiDetailException e) {
                actual =
                        JSON.valueToTree(
                                Map.of("status", e.status().value(), "detail", e.detail()));
            }
            assertThat(actual).as(c.path("id").asText()).isEqualTo(c.path("expected"));
        }
    }

    @Test
    void strictJsonRejectsTrailingTokensInvalidUtf8AndEmpty() {
        for (byte[] bytes :
                List.of(
                        "{\"message\":\"x\"} {\"x\":1}".getBytes(),
                        new byte[] {(byte) 0xff},
                        new byte[0])) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContentType("application/json");
            request.setContent(bytes);
            assertThatThrownBy(() -> ChatRequestParser.parse(request, 10))
                    .isInstanceOfSatisfying(
                            ApiDetailException.class,
                            e -> {
                                assertThat(e.status().value()).isEqualTo(422);
                                assertThat(e.detail()).isEqualTo("request body must be valid JSON");
                            });
        }
    }

    @Test
    void multipartUploadsUseOriginalByteHashAndReplacementWhileSoNeverBecomesSource()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/form-data; boundary=x");
        request.addParameter("context", "");
        byte[] bytes = new byte[] {'x', (byte) 0xed, (byte) 0xa0};
        request.addPart(new MockPart("file", "1_学生_DNS.C", bytes));
        ChatInput input = ChatRequestParser.parse(request, 10);
        assertThat(input.message()).isEqualTo("请帮我评测这个插件（1_学生_DNS.C）");
        assertThat(input.attachment().source()).isEqualTo("x��");
        assertThat(input.attachment().protocolHint()).isEqualTo("dns");
        assertThat(input.attachment().contentHash())
                .isEqualTo(io.dpdk.assistant.task.TaskInputHash.sha256Hex(bytes));
        MockHttpServletRequest binary = new MockHttpServletRequest();
        binary.setContentType("multipart/form-data");
        binary.addPart(new MockPart("file", "plugin.so", bytes));
        assertThat(ChatRequestParser.parse(binary, 10).attachment().source()).isNull();
    }
}
