package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.source.SourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 来源回读装配：LI 动态表名来自配置（默认与 config.py 一致 li_knowledge_v1）。 */
@Configuration
public class SourceWiring {

    @Bean
    SourceRepository sourceRepository(TransactionRunner runner,
                                      @Value("${dpdk.llama-index-table:${LLAMA_INDEX_TABLE:li_knowledge_v1}}") String llamaIndexTable) {
        return new SourceRepository(runner, llamaIndexTable);
    }
}
