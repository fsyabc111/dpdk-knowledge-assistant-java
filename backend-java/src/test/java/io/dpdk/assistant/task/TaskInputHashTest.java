package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * input_hash oracle（验收 2 的纯函数面）：与冻结 Python
 * rag_issue_service.input_hash 的黄金向量逐字节一致。
 *
 * 黄金值由冻结源直接计算（合成 Unicode/附件字节），不是 Java 自证。
 */
class TaskInputHashTest {

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void matchesPythonGoldenVectors() {
        // ["问题",null,null] 紧凑 JSON → sha256
        assertThat(TaskInputHash.compute("问题"))
                .isEqualTo("2b2cb6f8a2bf4fd34af9ff4a8d2c7ba15d5ae56a54fcf28b8ebef09b67d620b6");
        // NFC 合成（e + 组合尖音符）与预组合 é 同一身份；首尾空白剥除
        assertThat(TaskInputHash.compute(" e\u0301 \n"))
                .isEqualTo("8e05b2d1e8c6954d1e9f4da4b2d4aa2cf7a7dba8a98317fcf33c20269b68ace2");
        assertThat(TaskInputHash.compute("é"))
                .isEqualTo("8e05b2d1e8c6954d1e9f4da4b2d4aa2cf7a7dba8a98317fcf33c20269b68ace2");
        // Python strip 边界：NBSP/全角空格剥除后与「问题」同 hash
        assertThat(TaskInputHash.compute("\u00a0问题\u3000"))
                .isEqualTo("2b2cb6f8a2bf4fd34af9ff4a8d2c7ba15d5ae56a54fcf28b8ebef09b67d620b6");
        // 内部空白参与身份（不压缩）
        assertThat(TaskInputHash.compute("a  b"))
                .isEqualTo("4f0743a7172d000a33dde0aba15cca90149919831e5c46ea9aadccb42904157a");
        assertThat(TaskInputHash.compute("a  b")).isNotEqualTo(TaskInputHash.compute("a b"));
        // 控制字符/引号/反斜杠按 Python json 转义；非 ASCII 原样（ensure_ascii=False）
        assertThat(TaskInputHash.compute("a\u0001b\"c\\d"))
                .isEqualTo("52094096bb2b5e064246df21295e6787d9cb038e533b632dfc4f8233f67cf0e3");
        assertThat(TaskInputHash.compute("问🚀题"))
                .isEqualTo("dab5514df7f19e77424b55a2e9fd752df1f0109ecb5c8811010c39a5916af509");
        assertThat(TaskInputHash.compute(""))
                .isEqualTo("2b295d52b1ef0ae884682900cdb51d5c2c313750ea60d378558b43578a641a7a");
    }

    @Test
    void attachmentBytesAndFilenameParticipate() throws Exception {
        String bytesHash = sha256Hex("return 1;".getBytes(StandardCharsets.UTF_8));
        // ["x","plugin.c","<sha256>"] 分帧：文件名与内容 hash 各占一数组位
        assertThat(TaskInputHash.compute("x", "plugin.c", bytesHash))
                .isEqualTo("b18b0a0b08bbb2e6051a3acda450f3e684f2c23d19302c26eec85e580e1cc56d");
        // 同内容异文件名 → 身份不同（拆词歧义防护：bc/a.c vs ab/c 同构）
        assertThat(TaskInputHash.compute("a", "bc", bytesHash))
                .isNotEqualTo(TaskInputHash.compute("ab", "c", bytesHash));
        // 原始字节参与：0xFF 与 0xFE 解码后同文本但字节身份不同
        String ff = sha256Hex(new byte[]{(byte) 0xff});
        String fe = sha256Hex(new byte[]{(byte) 0xfe});
        assertThat(ff).isNotEqualTo(fe);
        assertThat(TaskInputHash.compute("x", "p.c", ff))
                .isNotEqualTo(TaskInputHash.compute("x", "p.c", fe));
        // 空字节附件与非附件不同（null vs "" 数组位）
        assertThat(TaskInputHash.compute("x", "a.c", sha256Hex(new byte[0])))
                .isEqualTo("a61948787300110b20e9860abeee9b891bfecda7c23f4e0626adae623b457491");
        assertThat(TaskInputHash.compute("x"))
                .isNotEqualTo(TaskInputHash.compute("x", "a.c", sha256Hex(new byte[0])));
    }

    @Test
    void missingAttachmentIdentityFailsClosed() {
        assertThatThrownBy(() -> TaskInputHash.compute("x", "p.c", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("附件缺少原始字节身份");
        assertThatThrownBy(() -> TaskInputHash.compute("x", "p.c", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loneSurrogateRejectedLikePythonUtf8Encode() {
        assertThatThrownBy(() -> TaskInputHash.compute("\ud800"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("孤立代理项");
    }
}
