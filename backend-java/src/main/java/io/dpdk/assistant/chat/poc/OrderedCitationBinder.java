package io.dpdk.assistant.chat.poc;

import io.dpdk.assistant.contracts.chat.Citation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import io.dpdk.assistant.contracts.chat.CitationBinder;
import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

/**
 * 引用绑定（J1-04 POC 实现；正式契约由 J3-03 接管）。
 *
 * 语义对齐 Python citations_from_chunks：保留全部片段及原序，同文件不同片段各占编号；
 * score = Python round(x, 4)（基于 double 精确二进制值的 HALF_EVEN，非 HALF_UP）；
 * title 为 chunk title 或回退 source_path（POC 的 RetrievedChunk 暂无 title 字段时用 chunkId）。
 */
@Component
public class OrderedCitationBinder implements CitationBinder {

    @Override
    public List<Citation> bindInOrder(List<RetrievedChunk> chunks) {
        java.util.ArrayList<Citation> out = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            String engine = c.chunkId() != null && c.chunkId().startsWith("li_") ? "li" : "legacy";
            out.add(new Citation(c.chunkId(), engine, pythonRound4(c.score()), i + 1));
        }
        return out;
    }

    /** Python round(x, 4)：对 double 的精确二进制值做 decimal HALF_EVEN。 */
    static double pythonRound4(double value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
