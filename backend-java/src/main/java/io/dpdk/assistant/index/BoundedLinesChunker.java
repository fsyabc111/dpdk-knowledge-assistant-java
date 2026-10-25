package io.dpdk.assistant.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Python bounded-lines-v2 原行为：code point 长度、splitlines、strip 与行号。 */
public final class BoundedLinesChunker {
    private static final int MAX = 2600;
    private static final String WS = "[\\x09-\\x0d\\x1c-\\x20\\x85\\xa0\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}]";
    private static final String WORD = "[\\p{L}\\p{N}_]";
    private static final Pattern FUNCTION = Pattern.compile(
            "^" + WS + "*(?:static" + WS + "+)?(?:inline" + WS + "+)?[A-Za-z_](?:"
                    + WORD + "|" + WS + "|[\\*\\(\\)]){1,120}" + WS + "+([A-Za-z_]" + WORD
                    + "*)" + WS + "*\\([^;]*\\)" + WS + "*\\{", Pattern.MULTILINE | Pattern.UNIX_LINES);
    private static final Pattern STRUCT = Pattern.compile(
            "^" + WS + "*typedef" + WS + "+struct" + WS + "+([A-Za-z_]" + WORD + "*)?",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    private static final Pattern PARAGRAPH = Pattern.compile("\\n" + WS + "*\\n");

    public record Chunk(String section, String content, Map<String, Object> metadata) {}
    private record Start(int line, String symbol) {}

    public List<Chunk> chunk(String raw, String relative) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        String extension = extension(relative);
        return List.of("md", "yml", "yaml").contains(extension.toLowerCase(java.util.Locale.ROOT))
                ? markdown(text, relative) : code(text, relative, extension);
    }

    private List<Chunk> markdown(String text, String relative) {
        List<Chunk> result = new ArrayList<>();
        String title = relative;
        List<String> current = new ArrayList<>();
        for (String line : splitlines(text)) {
            if (line.startsWith("#") && !current.isEmpty()) {
                String content = strip(String.join("\n", current));
                if (!content.isEmpty()) result.addAll(split(title, content, Map.of("source_type", "doc")));
                current.clear();
            }
            if (line.startsWith("#")) {
                title = strip(line.replaceFirst("^#+", ""));
                if (title.isEmpty()) title = relative;
            }
            current.add(line);
        }
        String content = strip(String.join("\n", current));
        if (!content.isEmpty()) result.addAll(split(title, content, Map.of("source_type", "doc")));
        return result;
    }

    private List<Chunk> code(String text, String relative, String language) {
        List<String> lines = splitlines(text);
        List<Start> starts = new ArrayList<>();
        var functions = FUNCTION.matcher(text);
        while (functions.find()) starts.add(new Start(countNewlines(text.substring(0, functions.start())), functions.group(1)));
        var structs = STRUCT.matcher(text);
        while (structs.find()) starts.add(new Start(countNewlines(text.substring(0, structs.start())),
                structs.group(1) == null ? "typedef struct" : structs.group(1)));
        // Python tuple.sort：行号相同还要按 symbol 的 code point 排序。
        starts.sort(Comparator.comparingInt(Start::line).thenComparing(Start::symbol, BoundedLinesChunker::compareCodePoints));
        if (starts.isEmpty()) return split(relative, text, Map.of("source_type", "code", "language", language));
        List<Chunk> result = new ArrayList<>();
        if (starts.getFirst().line() > 0) result.addAll(split(relative,
                String.join("\n", lines.subList(0, starts.getFirst().line())),
                Map.of("source_type", "code", "start_line", "1")));
        for (int i = 0; i < starts.size(); i++) {
            Start start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1).line() : lines.size();
            String content = strip(String.join("\n", lines.subList(start.line(), end)));
            if (!content.isEmpty()) result.addAll(split(start.symbol(), content,
                    Map.of("source_type", "code", "symbol", start.symbol(), "language", language,
                            "start_line", Integer.toString(start.line() + 1), "end_line", Integer.toString(end))));
        }
        return result;
    }

    private List<Chunk> split(String section, String content, Map<String, Object> metadata) {
        if (length(content) <= MAX) return List.of(new Chunk(section, content, metadata));
        List<String> pieces = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        for (String paragraph : PARAGRAPH.split(content, -1)) {
            List<String> candidate = new ArrayList<>(buffer);
            candidate.add(paragraph);
            if (length(String.join("\n\n", candidate)) > MAX && !buffer.isEmpty()) {
                pieces.add(strip(String.join("\n\n", buffer)));
                buffer.clear();
            }
            buffer.add(paragraph);
        }
        if (!buffer.isEmpty()) pieces.add(strip(String.join("\n\n", buffer)));
        List<Chunk> bounded = new ArrayList<>();
        for (String piece : pieces) {
            for (int start = 0; start < length(piece); start += MAX) {
                String part = substring(piece, start, Math.min(start + MAX, length(piece)));
                int found = Math.max(0, content.indexOf(piece));
                int utf16Offset = piece.offsetByCodePoints(0, start);
                int line = Integer.parseInt(metadata.getOrDefault("start_line", "1").toString())
                        + countNewlines(content.substring(0, found + utf16Offset));
                Map<String, Object> meta = new LinkedHashMap<>(metadata);
                meta.put("start_line", Integer.toString(line));
                meta.put("end_line", Integer.toString(line + countNewlines(part)));
                bounded.add(new Chunk(section, part, meta));
            }
        }
        return bounded;
    }

    static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1 && dot < path.length() - 1 ? path.substring(dot + 1) : "";
    }
    static int compareCodePoints(String left, String right) {
        var a = left.codePoints().iterator();
        var b = right.codePoints().iterator();
        while (a.hasNext() && b.hasNext()) {
            int diff = Integer.compare(a.nextInt(), b.nextInt());
            if (diff != 0) return diff;
        }
        return Boolean.compare(a.hasNext(), b.hasNext());
    }
    static String strip(String text) {
        return text.replaceAll("^" + WS + "+|" + WS + "+$", "");
    }
    private static int length(String text) { return text.codePointCount(0, text.length()); }
    private static String substring(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
    private static int countNewlines(String text) { return (int) text.chars().filter(c -> c == '\n').count(); }
    private static List<String> splitlines(String text) {
        if (text.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(text.split("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]", -1)));
        if (lines.getLast().isEmpty()) lines.removeLast();
        return lines;
    }
}
