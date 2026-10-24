package io.dpdk.assistant.platform.pg;

import io.dpdk.assistant.platform.config.AppProperties;

/**
 * 数据库身份预检失败：目标不是预期的隔离实例。此异常必须终止任何 DDL/业务写入尝试。
 */
public class DatabaseIdentityException extends RuntimeException {

    public DatabaseIdentityException(String message) {
        super(message);
    }

    /** 便于负例测试断言的安全摘要（不含密码/连接串）。 */
    public static String summarize(AppProperties.DatabaseIdentitySpec expected, Actual actual) {
        return "数据库身份不符: 期望 database=%s user=%s comment=%s dataDir=%s, 实际 database=%s user=%s comment=%s dataDir=%s"
                .formatted(expected.expectedDatabase(), expected.expectedUser(),
                        expected.expectedComment(), expected.expectedDataDir(),
                        actual.database(), actual.user(), actual.comment(), actual.dataDirectory());
    }

    public record Actual(String database, String user, String comment, String dataDirectory) {
    }
}
