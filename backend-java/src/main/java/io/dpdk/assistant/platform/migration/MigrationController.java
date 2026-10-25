package io.dpdk.assistant.platform.migration;

import io.dpdk.assistant.platform.config.AppProperties;
import io.dpdk.assistant.platform.pg.DatabaseIdentityGuard;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 唯一迁移入口（DDL 唯一 owner = 主 Agent/J1-01 集成者，契约 schema-migration.md）。
 *
 * 三条受控路径：
 * - 空库：baselineOnMigrate=true + baselineVersion=15，migrate 应用 B0015（V&lt;=15 不执行）；
 * - 合成0013：显式 baseline(13) 后 migrate 执行 V0014/V0015（已有 baseline 标记时 B0015 不执行）；
 * - 等价0015：显式 baseline(15) 后 migrate 空转（B0015 与 V&lt;=15 不重执行）；
 * - 已有 Flyway 历史：常规 migrate（validateOnMigrate=true）；
 * - 其余（部分表/混合列状态）：拒绝，不做任何写入。
 *
 * 常量设置：baselineOnMigrate=false（仅空库路径显式开启）、validateOnMigrate=true、cleanDisabled=true。
 */
@Component
public final class MigrationController {

    private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

    public record Outcome(CatalogProbe.Decision path, int migrationsApplied, boolean catalogVerified,
                          List<String> catalogDiffs) {
    }

    public Outcome run(DataSource dataSource, AppProperties.DatabaseIdentitySpec expectedIdentity,
                       boolean verifyCatalog) {
        try (Connection c = dataSource.getConnection()) {
            DatabaseIdentityGuard.verify(c, expectedIdentity);
        } catch (SQLException e) {
            throw new IllegalStateException("迁移前连接失败", e);
        }

        CatalogProbe.Decision decision;
        try (Connection c = dataSource.getConnection()) {
            decision = CatalogProbe.classify(c);
        } catch (SQLException e) {
            throw new IllegalStateException("迁移前探查失败", e);
        }
        log.info("迁移路径判定: {}", decision);

        int applied;
        switch (decision) {
            case EMPTY -> {
                Flyway f = Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("15")
                        .validateOnMigrate(true)
                        .cleanDisabled(true)
                        .load();
                MigrateResult r = f.migrate();
                applied = r.migrationsExecuted;
            }
            case AT_0013 -> applied = baselineThenMigrate(dataSource, "13");
            case AT_0015 -> applied = baselineThenMigrate(dataSource, "15");
            case HISTORY -> {
                MigrateResult r = Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .validateOnMigrate(true)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
                applied = r.migrationsExecuted;
            }
            case REFUSE -> throw new IllegalStateException(
                    "目标 catalog 不属于三条受控路径（空库/合成0013/等价0015），拒绝迁移；"
                            + "请先人工核对该库来源，禁止盲 stamp 或猜测基线。");
            default -> throw new IllegalStateException("未覆盖的迁移判定: " + decision);
        }

        List<String> diffs = List.of();
        boolean verified = false;
        if (verifyCatalog) {
            // 非空起点可能尚未创建 LI 动态表（由索引摄取生成），作为可选表比对。
            boolean liOptional = decision != CatalogProbe.Decision.EMPTY;
            diffs = CatalogVerifier.verify(dataSource, liOptional);
            verified = diffs.isEmpty();
            if (!verified) {
                throw new IllegalStateException("迁移后 catalog 差分未清零: " + diffs);
            }
        }
        return new Outcome(decision, applied, verified, diffs);
    }

    private int baselineThenMigrate(DataSource dataSource, String baselineVersion) {
        Flyway baselineFlyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion(baselineVersion)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
        baselineFlyway.baseline();
        MigrateResult r = baselineFlyway.migrate();
        return r.migrationsExecuted;
    }
}
