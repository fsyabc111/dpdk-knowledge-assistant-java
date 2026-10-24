package io.dpdk.assistant.contracts.retrieval;

import java.util.Optional;

/**
 * 只读检索端口（owner J1-03；契约 transaction-ports.json：retrieval.ReleaseReader）。
 *
 * 唯一 LI 数据面；固定 release 后候选/重排/引用身份不漂移；
 * legacy knowledge_chunks 仅历史来源回读；1024 维固定向量合成测试，不调 embedding。
 */
public interface ReleaseReader {

    ReleaseSnapshot pin(String releaseId);

    SearchResult searchPinned(ReleaseSnapshot snapshot, SearchInput input);

    Optional<SourceChunk> historical(String engine, String chunkId);
}
