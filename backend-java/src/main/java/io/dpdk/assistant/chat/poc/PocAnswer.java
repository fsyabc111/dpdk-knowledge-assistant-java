package io.dpdk.assistant.chat.poc;

import io.dpdk.assistant.contracts.chat.Citation;
import java.util.List;

/**
 * POC 问答结果（J1-04 候选 DTO 草案，正式 ChatResponse 归 J3-03/J0-01 契约）。
 *
 * verdict 语义对齐 Python _precheck/_postcheck：
 * answered / unanswered；failureCode ∈ {empty_answer, invalid_citation, no_evidence,
 * missing_citation, system_error}。成功路径 answer 非空且 citations 与证据编号一一对应。
 */
public record PocAnswer(String answer, List<Citation> citations, String answerStatus, String failureCode) {

    public static PocAnswer answered(String answer, List<Citation> citations) {
        return new PocAnswer(answer, citations, "answered", null);
    }

    public static PocAnswer unanswered(String failureCode) {
        return new PocAnswer("", List.of(), "unanswered", failureCode);
    }
}
