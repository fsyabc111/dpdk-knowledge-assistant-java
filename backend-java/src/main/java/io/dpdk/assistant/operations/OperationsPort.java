package io.dpdk.assistant.operations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 运维消费端口；适配器归集成者，复用各业务模块，禁止在 CLI 复制业务 SQL。 */
public interface OperationsPort {
    record CreateUsers(List<String> ids, List<String> roles, Integer dailyQuota) {
        public CreateUsers { ids = List.copyOf(ids); roles = List.copyOf(roles); }
    }
    Map<String, Object> createUsers(CreateUsers request, boolean apply);
    Map<String, Object> purgeSessions(int retentionDays, boolean apply);
    Map<String, Object> purgeTaskInputs(boolean apply);
    Map<String, Object> retainIssues(int retentionDays, boolean apply);
    Map<String, Object> ingest(Path manifest, boolean apply);
    Map<String, Object> rollback(String release, boolean apply);
    Map<String, Object> auditGradingJobs(int limit);
    Map<String, Object> retrieve(String query, int topK);
    Map<String, Object> judge(Path cases);
}
