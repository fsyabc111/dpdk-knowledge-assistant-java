package io.dpdk.assistant.contracts.identity;

import java.util.List;

/** Principal（契约 transaction-ports.json：identity.PrincipalResolver.data_fields）。 */
public record Principal(String subject, List<String> roles, int dailyQuota, boolean mustChangePassword) {
}
