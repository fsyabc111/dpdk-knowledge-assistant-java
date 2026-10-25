package io.dpdk.assistant.agent.tools;

import io.dpdk.assistant.agent.context.CodePoints;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 围栏源码抽取（B6 带外执行源，源 backend/app/agent/tools.py:extract_fenced_source）。
 *
 * <p>同时认 ``` 与 ~~~ 两种开围栏（含语言标注行与未闭合尾围栏），多个块取
 * 内容最长者（码点口径）；反向引用保证 ``` 块不会被 ~~~ 误闭合（反之亦然）。
 * 抽取内容剥掉首尾空行并补回行尾换行，与 .c 原件按文本读取的字节口径对齐。
 */
public final class FencedSource {

    /**
     * 围栏代码块：``` 与 ~~~ 两种开围栏 + 可选语言标注行；尾围栏未闭合时
     * 其后内容整体视为源码。反向引用 \1 保证配对闭合。
     */
    private static final Pattern FENCED_SOURCE_RE =
            Pattern.compile("(```|~~~)[^\\n]*\\n([\\s\\S]*?)(?:\\1|$)");

    private FencedSource() {
    }

    /** 从用户消息中抽取围栏源码；无围栏块（或块内容为空白）返回 null。 */
    public static String extract(String text) {
        if (text == null) {
            text = "";
        }
        String best = null;
        Matcher matcher = FENCED_SOURCE_RE.matcher(text);
        while (matcher.find()) {
            String content = CodePoints.stripNewlines(matcher.group(2));
            if (content.isBlank()) {
                continue;
            }
            if (best == null || CodePoints.length(content) > CodePoints.length(best)) {
                best = content;
            }
        }
        return best == null ? null : best + "\n";
    }
}
