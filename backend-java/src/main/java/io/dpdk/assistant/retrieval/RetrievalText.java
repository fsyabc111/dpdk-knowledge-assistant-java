package io.dpdk.assistant.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索文本特征（逐项移植 retrieval_service.py 的正则与分词器）。
 *
 * Python 3 的 \w/\s/\b 默认 Unicode 语义；这里以 UNICODE_CHARACTER_CLASS 对齐。
 * ASCII_TOKEN/CJK_TOKEN/IDENTIFIER 为显式 ASCII/区间类，与 Python 逐字相同。
 */
public final class RetrievalText {

    /** 文件名 token（\w 为 Unicode 词字符，与 Python 一致；忽略大小写）。 */
    public static final Pattern FILENAME_RE = Pattern.compile(
            "[\\w_-]+\\.(?:c|h|cpp|hpp|py|js|jsx|ts|tsx|md|yml|yaml)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** 2 个及以上的 CJK 连续串。 */
    public static final Pattern CJK_TOKEN_RE = Pattern.compile("[\\x{4e00}-\\x{9fff}]{2,}");

    /** 2 个及以上的 ASCII 词字符串。 */
    public static final Pattern ASCII_TOKEN_RE = Pattern.compile("[A-Za-z0-9_]{2,}");

    /** C/风格标识符（≥3 字符）。 */
    public static final Pattern IDENTIFIER_RE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");

    /** 函数签名定义行（definition_boost 用）。 */
    public static final Pattern FUNCSIG_RE = Pattern.compile(
            "\\b(?:static\\s+)?(?:inline\\s+)?[A-Za-z_][\\w\\s\\*\\(\\)]{1,120}\\s+[A-Za-z_]\\w*\\s*\\(",
            Pattern.UNICODE_CHARACTER_CLASS);

    private RetrievalText() {
    }

    /** Python re.findall（无捕获组 → 整体匹配）等价物：按出现顺序返回全部匹配。 */
    public static List<String> findAll(Pattern pattern, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Python str.lower()（语言无关确定性）。 */
    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** _text_tokens：ASCII(lower) ∪ CJK(原文) 的 token 集合。 */
    public static Set<String> textTokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(findAll(ASCII_TOKEN_RE, lower(text)));
        out.addAll(findAll(CJK_TOKEN_RE, text));
        return out;
    }

    /** token Jaccard（空集为 0）。 */
    public static double tokenJaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        int inter = 0;
        for (String t : left) {
            if (right.contains(t)) {
                inter++;
            }
        }
        return (double) inter / union.size();
    }

    /** _query_mentions_keyword：ASCII 词按边界匹配，非 ASCII 按包含。 */
    public static boolean queryMentionsKeyword(String queryLower, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }
        if (isAscii(keyword)) {
            String quoted = Pattern.quote(lower(keyword));
            return Pattern.compile("(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])")
                    .matcher(queryLower).find();
        }
        return queryLower.contains(keyword);
    }

    /** Python str.isascii()。 */
    public static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    /** Python str.isalpha()（近似：全部为 Unicode 字母；CJK 字母属于 Lo）。 */
    public static boolean isAlpha(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(Character::isLetter);
    }

    /**
     * _fts_terms：ASCII token（lower，去重保序）在前、CJK token 在后，截前 10。
     */
    public static List<String> ftsTerms(String query) {
        List<String> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : findAll(ASCII_TOKEN_RE, lower(query))) {
            if (seen.add(token)) {
                terms.add(token);
            }
        }
        for (String token : findAll(CJK_TOKEN_RE, query)) {
            if (seen.add(token)) {
                terms.add(token);
            }
        }
        return terms.size() > 10 ? new ArrayList<>(terms.subList(0, 10)) : terms;
    }

    /** _build_fts_query：'\"t1\" OR \"t2\"'；无词返回 null。 */
    public static String buildFtsQuery(String query) {
        List<String> terms = ftsTerms(query);
        if (terms.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append('"').append(terms.get(i)).append('"');
        }
        return sb.toString();
    }

    /**
     * _path_fragments：各 query 先取文件名 token（按 lower 去重、保留原文大小写），
     * 再按 TOPIC_PATH_KEYWORDS 键序追加被提及关键词的路径片段。
     */
    public static List<String> pathFragments(String... queries) {
        List<String> fragments = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            for (String filename : findAll(FILENAME_RE, query)) {
                if (seen.add(lower(filename))) {
                    fragments.add(filename);
                }
            }
            String queryLower = lower(query);
            for (var entry : TopicPaths.TOPIC_PATH_KEYWORDS.entrySet()) {
                if (queryMentionsKeyword(queryLower, entry.getKey())) {
                    for (String path : entry.getValue()) {
                        if (seen.add(lower(path))) {
                            fragments.add(path);
                        }
                    }
                }
            }
        }
        return fragments;
    }
}
