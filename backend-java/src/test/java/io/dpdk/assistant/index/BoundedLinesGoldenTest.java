package io.dpdk.assistant.index;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** 直接消费先行冻结的 Python 结果，禁止以 Java 输出重写黄金。 */
class BoundedLinesGoldenTest {
    @TestFactory
    Stream<DynamicTest> pythonGolden() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var stream = getClass().getResourceAsStream("/migration/J4-01/chunker-golden.json");
        var cases = json.readTree(stream).get("cases");
        return java.util.stream.StreamSupport.stream(cases.spliterator(), false).map(row ->
                DynamicTest.dynamicTest(row.get("case").asText(), () -> {
                    var actual = new BoundedLinesChunker().chunk(row.get("input").asText(), row.get("path").asText());
                    com.fasterxml.jackson.databind.JsonNode tree = json.valueToTree(actual);
                    assertThat(tree).isEqualTo(row.get("chunks"));
                }));
    }
}
