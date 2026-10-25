package io.dpdk.assistant.index;

import java.nio.file.Path;
import java.util.Objects;

/** 显式知识源配置；不读取旧 .env，不猜测外部仓库。 */
public record SourceSettings(Path knowledgeConfig, Path dpdkRoot, Path assistantRoot, Path workingDirectory) {
    public SourceSettings {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        workingDirectory = workingDirectory.toAbsolutePath();
        knowledgeConfig = absolute(knowledgeConfig, workingDirectory);
        dpdkRoot = absolute(dpdkRoot, workingDirectory);
        assistantRoot = absolute(assistantRoot, workingDirectory);
    }

    private static Path absolute(Path value, Path cwd) {
        Objects.requireNonNull(value, "知识源路径");
        return value.isAbsolute() ? value : cwd.resolve(value);
    }
}
