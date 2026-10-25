package io.dpdk.assistant.session;

/**
 * 维护端点设置（对应 Python Settings.maintenance_api_key / session_retention_days）。
 *
 * maintenance_api_key 默认空串：未配置时维护端点一律 503（不静默放行也不误报 403）；
 * session_retention_days 默认 30。
 */
public record MaintenanceSettings(String maintenanceApiKey, int sessionRetentionDays) {

    public static MaintenanceSettings defaults() {
        return new MaintenanceSettings("", 30);
    }
}
