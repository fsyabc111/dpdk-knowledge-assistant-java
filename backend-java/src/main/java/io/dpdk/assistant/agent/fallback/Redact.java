package io.dpdk.assistant.agent.fallback;

import io.dpdk.assistant.agent.context.CodePoints;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话/预览消毒（源 backend/app/services/rag_issue_service.py:redact，B8 口径统一）。
 *
 * <p>围栏块认 ``` 与 ~~~ 两种（与 extract_fenced_source 同口径，反向引用配对闭合）；
 * 附件标记、学号、疑似凭据/代码行整体省略，避免截短后留下秘密的前缀。
 * 粘贴的源码不进会话正文/降级预览/长期材料（源码哨兵不漏出）。
 */
public final class Redact {

    private static final Pattern FENCE_RE = Pattern.compile("(```|~~~)[\\s\\S]*?(?:\\1|$)");
    private static final Pattern ATTACHMENT_RE = Pattern.compile("\\[上传附件[：:][^\\]]*\\]");
    private static final Pattern STUDENT_ID_RE = Pattern.compile("\\bU\\d{11}\\b");
    private static final Pattern SENSITIVE_LINE_RE = Pattern.compile(
            "(?i)(#include|\\b(?:api[_-]?key|password|authorization|bearer|token|secret)\\b\\s*[:= ]"
                    + "|sk-[\\w-]{8,}|\\b(?:return|typedef|struct)\\b.*[;{}]|[;{}]\\s*$)");
    private static final Pattern EMAIL_RE =
            Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_DIGITS_RE = Pattern.compile("(?<!\\d)\\d{7,}(?!\\d)");

    private Redact() {
    }

    public static String redact(String text) {
        if (text == null) {
            text = "";
        }
        String cleaned = FENCE_RE.matcher(text).replaceAll("[代码已省略]");
        cleaned = ATTACHMENT_RE.matcher(cleaned).replaceAll("[附件已省略]");
        cleaned = STUDENT_ID_RE.matcher(cleaned).replaceAll("[学号已省略]");
        List<String> lines = new ArrayList<>();
        for (String line : cleaned.split("\n", -1)) {
            if (SENSITIVE_LINE_RE.matcher(line).find()) {
                lines.add("[敏感内容已省略]");
                continue;
            }
            line = EMAIL_RE.matcher(line).replaceAll("[邮箱已省略]");
            line = LONG_DIGITS_RE.matcher(line).replaceAll("[号码已省略]");
            lines.add(line);
        }
        return CodePoints.truncate(String.join("\n", lines), 12000);
    }
}
