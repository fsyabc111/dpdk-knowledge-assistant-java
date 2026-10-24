package io.dpdk.assistant;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Java 迁移主后端入口。
 *
 * 数据库与迁移纪律：应用不使用 Boot 的自动 Flyway/SQL 初始化（见 application.yml），
 * 由 {@code platform.migration.MigrationController} 在完成数据库身份预检后按
 * 空库 B0015 / 合成0013 / 等价0015 三条受控路径执行迁移（契约 schema-migration.md）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
