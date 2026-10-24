package io.dpdk.assistant.platform.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动期 fail-closed 校验：配置缺失、非 PG 协议、非回环地址、默认端口或身份期望缺失
 * 都必须让启动失败。错误信息只包含非秘密字段，绝不回显密码。
 */
public final class ConfigValidator {

    /** 宿主机默认 PG 端口：即使显式写出也视为危险目标，拒绝。 */
    public static final int FORBIDDEN_PORT = 5432;

    private ConfigValidator() {
    }

    public static void validate(AppProperties props) {
        if (props == null) {
            throw new IllegalStateException("dpdk.* 配置缺失");
        }
        List<String> problems = new ArrayList<>();
        var ds = props.datasource();
        if (ds == null) {
            problems.add("dpdk.datasource 未配置");
        } else {
            if (isBlank(ds.url())) {
                problems.add("dpdk.datasource.url 缺失（无默认值，fail-closed）");
            } else {
                problems.addAll(validateUrl(ds.url()));
            }
            if (isBlank(ds.username())) {
                problems.add("dpdk.datasource.username 缺失");
            }
            // password 允许空串（隔离实例 trust 场景），但不允许缺失配置键以外的情况——空串即可。
        }
        var id = props.databaseIdentity();
        if (id == null) {
            problems.add("dpdk.database-identity 未配置");
        } else {
            if (isBlank(id.expectedDatabase())) {
                problems.add("expected-database 缺失");
            }
            if (isBlank(id.expectedUser())) {
                problems.add("expected-user 缺失");
            }
            if (isBlank(id.expectedComment())) {
                problems.add("expected-comment 缺失（数据库 comment 身份标记）");
            }
            if (isBlank(id.expectedDataDir())) {
                problems.add("expected-data-dir 缺失（data_directory 前缀校验）");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("配置校验失败（fail-closed）: " + String.join("; ", problems));
        }
    }

    private static List<String> validateUrl(String url) {
        List<String> problems = new ArrayList<>();
        if (!url.startsWith("jdbc:postgresql://")) {
            problems.add("dpdk.datasource.url 必须是 jdbc:postgresql:// 协议");
            return problems;
        }
        try {
            // 保留 authority 结构（//host:port/db），否则 host 会被当成 scheme 解析成 null。
            URI uri = URI.create("//" + url.substring("jdbc:postgresql://".length()));
            String host = uri.getHost();
            int port = uri.getPort();
            String normalizedHost = host == null ? null
                    : host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            if (normalizedHost == null) {
                problems.add("URL 缺少主机名");
            } else if (!normalizedHost.equals("127.0.0.1") && !normalizedHost.equals("::1")
                    && !normalizedHost.equals("localhost")) {
                problems.add("只允许回环地址（127.0.0.1/::1/localhost），拒绝: " + normalizedHost);
            }
            if (port == -1) {
                problems.add("URL 必须显式指定端口（不接受默认端口隐式回退）");
            } else if (port == FORBIDDEN_PORT) {
                problems.add("端口 5432 是宿主机默认 PG，禁止作为目标");
            }
        } catch (IllegalArgumentException e) {
            problems.add("URL 解析失败");
        }
        return problems;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
