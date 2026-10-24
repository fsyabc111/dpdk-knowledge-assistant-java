package io.dpdk.assistant.chat.poc;

import io.dpdk.assistant.contracts.chat.Citation;
import io.dpdk.assistant.contracts.model.ChatInput;
import io.dpdk.assistant.contracts.model.ChatModelPort;
import io.dpdk.assistant.contracts.retrieval.ReleaseReader;
import io.dpdk.assistant.contracts.retrieval.ReleaseSnapshot;
import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.contracts.retrieval.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小问答纵向切片（J1-04）：请求 → 固定版本检索 → 模型 → 结构化答案/引用/裁决。
 *
 * 仅测试装配入口，不占用正式 /api/chat* 路由；不提供持久会话/任务/生产 SSE。
 * 裁决顺序对齐 Python：empty_answer → invalid_citation（送审前）；no_evidence →
 * missing_citation（可判 answered 的后置复核，POC 无工具故 tools 恒空）。
 */
public class MinimalQaSlice {

    /** 模型答案中的证据编号引用，形如 [1]、[2][4]。 */
    static final Pattern REF_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final ReleaseReader releaseReader;
    private final ChatModelPort chatModel;

    public MinimalQaSlice(ReleaseReader releaseReader, ChatModelPort chatModel) {
        this.releaseReader = releaseReader;
        this.chatModel = chatModel;
    }

    public PocAnswer ask(String releaseId, String question) {
        return ask(releaseId, question, null);
    }

    /** 带固定查询向量的入口（J1-04 全链路集成用；替身检索可忽略向量）。 */
    public PocAnswer ask(String releaseId, String question,
                         io.dpdk.assistant.contracts.model.Vector1024 queryVector) {
        List<RetrievedChunk> chunks;
        try {
            ReleaseSnapshot snapshot = releaseReader.pin(releaseId);
            SearchResult result = releaseReader.searchPinned(snapshot,
                    new SearchInput(question, queryVector));
            chunks = result.chunks();
        } catch (RuntimeException e) {
            return PocAnswer.unanswered("system_error");
        }

        String answer;
        try {
            answer = chatModel.complete(new ChatInput("poc", buildPrompt(question, chunks))).content();
        } catch (RuntimeException e) {
            return PocAnswer.unanswered("system_error");
        }

        List<Integer> refs = extractRefs(answer);
        // precheck：empty_answer → invalid_citation（顺序即现语义）。
        if (answer == null || answer.strip().isEmpty()) {
            return PocAnswer.unanswered("empty_answer");
        }
        for (int ref : refs) {
            if (ref < 1 || ref > chunks.size()) {
                return PocAnswer.unanswered("invalid_citation");
            }
        }
        // postcheck（无 tools）：no_evidence → missing_citation。
        if (chunks.isEmpty()) {
            return PocAnswer.unanswered("no_evidence");
        }
        if (refs.isEmpty()) {
            return PocAnswer.unanswered("missing_citation");
        }

        List<Citation> citations = new OrderedCitationBinder().bindInOrder(chunks);
        return PocAnswer.answered(answer, citations);
    }

    private String buildPrompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(question).append("\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(chunks.get(i).text()).append('\n');
        }
        return sb.toString();
    }

    static List<Integer> extractRefs(String answer) {
        List<Integer> refs = new ArrayList<>();
        if (answer == null) {
            return refs;
        }
        Matcher m = REF_PATTERN.matcher(answer);
        while (m.find()) {
            refs.add(Integer.parseInt(m.group(1)));
        }
        return refs;
    }
}
