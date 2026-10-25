package io.dpdk.assistant.operations.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowService;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.TaskStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 纯接线检查：证据裁决不得静默回退检索；无需数据库或模型。 */
class OperationsBusinessAdapterTest {
    @Test void legacyConstructorLeavesEvidenceJudgeExplicitlyUnavailable() {
        var adapter=new OperationsBusinessAdapter(mock(AdminUserRepository.class),mock(SessionService.class),mock(TaskStore.class),
                mock(KnowledgeWorkflowService.class),null,null,path->{throw new AssertionError();},Map.of());
        assertThatThrownBy(()->adapter.judge(Path.of("/tmp/cases.yml")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("离线证据 judge 未装配");
    }
    @Test void injectedJudgeRunsIndependentlyOfIndexAndFixedRetrieval() {
        AtomicInteger called=new AtomicInteger();
        var adapter=new OperationsBusinessAdapter(mock(AdminUserRepository.class),mock(SessionService.class),mock(TaskStore.class),
                mock(KnowledgeWorkflowService.class),null,null,path->{throw new AssertionError();},Map.of(),path->{
                    called.incrementAndGet();assertThat(path).isEqualTo(Path.of("/tmp/cases.yml"));
                    return Map.of("domain","offline_evidence_judge");
                });
        assertThat(adapter.judge(Path.of("/tmp/cases.yml"))).containsEntry("domain","offline_evidence_judge");
        assertThat(called).hasValue(1);
        assertThatThrownBy(()->adapter.judge(Path.of("relative.yml"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(called).hasValue(1);
    }
}
