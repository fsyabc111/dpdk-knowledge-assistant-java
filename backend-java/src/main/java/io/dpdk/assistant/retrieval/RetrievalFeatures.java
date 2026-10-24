package io.dpdk.assistant.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启发式重排特征函数（逐项移植 retrieval_service.py）：
 * filename_boost / topic_boost / keyword_score / identifier_score / definition_boost。
 *
 * 差异登记：filename_boost 在多个文件名同时命中且分别触发 exact/partial 时，
 * Python 依赖 set 迭代序（进程内不确定）；Java 按 findall 出现序取第一个命中，
 * 行为确定且覆盖源单文件名场景（见 handoff 差异表）。
 */
public final class RetrievalFeatures {

    private RetrievalFeatures() {
    }

    /** 文件名显式提及加成。 */
    public static double filenameBoost(String query, String sourcePath, RetrievalSettings s) {
        Set<String> files = new LinkedHashSet<>(RetrievalText.findAll(RetrievalText.FILENAME_RE, query));
        if (files.isEmpty()) {
            return 0.0;
        }
        String sourceLower = RetrievalText.lower(sourcePath);
        for (String f : files) {
            if (sourceLower.endsWith(RetrievalText.lower(f))) {
                return s.filenameBoostExact();
            }
            if (sourceLower.contains(RetrievalText.lower(f))) {
                return s.filenameBoostPartial();
            }
        }
        return 0.0;
    }

    /** 主题词加成：扫全部命中关键词取最大（特定词压过通用词）。 */
    public static double topicBoost(String query, String sourcePath, RetrievalSettings s) {
        String queryLower = RetrievalText.lower(query);
        String sourceLower = RetrievalText.lower(sourcePath);
        double best = 0.0;
        for (var entry : TopicPaths.TOPIC_PATH_KEYWORDS.entrySet()) {
            String keyword = entry.getKey();
            if (!RetrievalText.queryMentionsKeyword(queryLower, keyword)) {
                continue;
            }
            boolean specific = TopicPaths.SPECIFIC_TOPIC_KEYWORDS.contains(keyword);
            for (String p : entry.getValue()) {
                String pathLower = RetrievalText.lower(p);
                double candidate;
                if (sourceLower.endsWith(pathLower)) {
                    candidate = pathLower.contains(".")
                            ? (specific ? s.topicBoostSpecific() : s.topicBoostGeneric())
                            : (specific ? s.topicBoostSpecificBare() : s.topicBoostGenericBare());
                } else if (sourceLower.contains(pathLower)) {
                    candidate = specific ? s.topicBoostSpecificInfix() : s.topicBoostGenericInfix();
                } else {
                    continue;
                }
                if (candidate > best) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** 词法命中率：空白分词（不拆下划线），分母 max(len(terms),1)。 */
    public static double keywordScore(String query, String content, Map<String, Object> metadata) {
        String haystack = RetrievalText.lower(
                content + " " + PythonJson.dumpsUnicode(metadata == null ? Map.of() : metadata));
        List<String> terms = new java.util.ArrayList<>();
        // Python str.split()：任意空白分隔、丢弃空段；lower 先于 strip（顺序与源一致）。
        for (String part : query.split("\\s+")) {
            String t = RetrievalText.lower(part).strip();
            if (!t.isEmpty()) {
                terms.add(t);
            }
        }
        if (terms.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                hits++;
            }
        }
        return (double) hits / Math.max(terms.size(), 1);
    }

    /** 标识符得分：header（路径+栏目）命中优先于正文；去重后平均并封顶 1.0。 */
    public static double identifierScore(
            String query, String sourcePath, String section, String content, RetrievalSettings s) {
        List<String> identifiers = new java.util.ArrayList<>();
        for (String id : RetrievalText.findAll(RetrievalText.IDENTIFIER_RE, query)) {
            identifiers.add(RetrievalText.lower(id));
        }
        if (identifiers.isEmpty()) {
            return 0.0;
        }
        String header = RetrievalText.lower(sourcePath + " " + section);
        String body = RetrievalText.lower(content);
        double score = 0.0;
        for (String identifier : new LinkedHashSet<>(identifiers)) {
            if (header.contains(identifier)) {
                score += s.identHeaderScore();
            } else if (body.contains(identifier)) {
                score += s.identBodyScore();
            }
        }
        return Math.min(score / Math.max(new LinkedHashSet<>(identifiers).size(), 1), 1.0);
    }

    /** 结构定义加成：typedef struct/enum 或函数签名行。 */
    public static double definitionBoost(String content, RetrievalSettings s) {
        String stripped = content.stripLeading();
        if (stripped.startsWith("typedef struct") || stripped.startsWith("typedef enum")) {
            return s.definitionBoostTypedef();
        }
        if (RetrievalText.FUNCSIG_RE.matcher(stripped).find()) {
            return s.definitionBoostFuncsig();
        }
        return 0.0;
    }
}
