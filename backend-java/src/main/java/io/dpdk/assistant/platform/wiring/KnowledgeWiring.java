package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowService;
import io.dpdk.assistant.knowledge.workflow.OriginalQuestionVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识工作流共享装配；原题验证消费产品侧强制judge端口。 */
@Configuration
public class KnowledgeWiring {
    @Bean
    KnowledgeWorkflowService knowledgeWorkflowService(TransactionRunner tx, ClockPort clock,
                                                      OriginalQuestionVerifier verifier) {
        return new KnowledgeWorkflowService(tx, clock, verifier);
    }
}
