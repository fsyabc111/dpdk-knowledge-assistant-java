package io.dpdk.assistant.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlKnowledgeSourceTest {
    @TempDir Path temp;

    private SourceSettings config(String sources) throws Exception {
        Path yaml = temp.resolve("sources.yml");
        Files.writeString(yaml, "sources:\n" + sources);
        return new SourceSettings(yaml, temp, temp, temp);
    }
    private void file(String name, String text) throws Exception {
        Path path = temp.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    @Test void duplicatesLongestRootAndSameLengthTie() throws Exception {
        file("repo/a.md", "# A\n内容"); file("repo/nested/b.c", "int f(void) {return 1;}");
        var settings = config("  - name: first\n    root: ${DPDK_REPO_PATH}/repo\n    include: [a.md, nested]\n"
                + "  - name: same\n    root: ${DPDK_REPO_PATH}/repo\n    include: [a.md]\n"
                + "  - name: inner\n    root: ${ASSISTANT_PROJECT_PATH}/repo/nested\n    include: [b.c]\n");
        var docs = new YamlKnowledgeSource().scan(settings);
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).metadata()).containsEntry("source_key", "first/a.md");
        assertThat(docs.get(1).metadata()).containsEntry("source_key", "inner/b.c");
    }

    @Test void pythonPathPartsAndCodePointSorting() throws Exception {
        file("a/b.md", "B"); file("a-z.md", "Z"); file("\ue000.md", "BMP"); file("🙂.md", "ASTRAL");
        var docs = new YamlKnowledgeSource().scan(config("  - name: root\n    root: ${DPDK_REPO_PATH}\n    include: [a, a-z.md, '\ue000.md', '🙂.md']\n"));
        assertThat(docs.stream().map(d -> d.metadata().get("source_path"))).containsExactly("a/b.md", "a-z.md", "\ue000.md", "🙂.md");
    }

    @Test void exactSuffixFilteringUniversalNewlinesAndEmptyConfiguration() throws Exception {
        file("a.MD", "# 标题\r\n内容\r尾\n"); file("not-indexed.txt", "忽略");
        var docs = new YamlKnowledgeSource().scan(config("  - name: repo\n    root: ${DPDK_REPO_PATH}\n    include: [.]\n"));
        // sources.yml 本身是受支持源（完整目录扫描行为）；仅核查目标文件及排除项。
        assertThat(docs.stream().filter(d -> "a.MD".equals(d.metadata().get("source_path"))).findFirst().orElseThrow().content()).isEqualTo("# 标题\n内容\n尾");
        assertThat(docs.stream().map(d -> d.metadata().get("source_path"))).doesNotContain("not-indexed.txt");
        Files.writeString(temp.resolve("sources.yml"), "");
        assertThat(new YamlKnowledgeSource().scan(new SourceSettings(temp.resolve("sources.yml"), temp, temp, temp))).isEmpty();
    }

    @Test void missingIncludeAndGlobAndUnsupportedExplicitFileFail() throws Exception {
        for (String path : new String[]{"missing.md", "*.md", "a.txt"}) {
            file("a.txt", "text");
            var settings = config("  - name: repo\n    root: ${DPDK_REPO_PATH}\n    include: ['" + path + "']\n");
            assertThatThrownBy(() -> new YamlKnowledgeSource().scan(settings)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void directAndDirectorySymlinkEscapesFail() throws Exception {
        file("outside.md", "secret"); Files.createDirectories(temp.resolve("repo"));
        Files.createSymbolicLink(temp.resolve("repo/link.md"), temp.resolve("outside.md"));
        for (String include : new String[]{"link.md", ".", "../outside.md"}) {
            var settings = config("  - name: repo\n    root: ${DPDK_REPO_PATH}/repo\n    include: ['" + include + "']\n");
            assertThatThrownBy(() -> new YamlKnowledgeSource().scan(settings)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void safeYamlRejectsArbitraryTags() throws Exception {
        Files.writeString(temp.resolve("evil.yml"), "!!java.lang.ProcessBuilder ['touch', 'bad']");
        var settings = new SourceSettings(temp.resolve("evil.yml"), temp, temp, temp);
        assertThatThrownBy(() -> new YamlKnowledgeSource().scan(settings)).isInstanceOf(org.yaml.snakeyaml.error.YAMLException.class);
        assertThat(temp.resolve("bad")).doesNotExist();
    }
    @Test void realpathBeforeDotDotAndPosixBackslashIdentity() throws Exception {
        file("target/nested/unused.md", "unused"); file("target/a\\b.md", "backslash");
        Files.createDirectories(temp.resolve("root"));
        Files.createSymbolicLink(temp.resolve("root/link"), temp.resolve("target/nested"));
        Path yaml = temp.resolve("symlink-root.yml");
        Files.writeString(yaml, "sources:\n  - name: repo\n    root: ${DPDK_REPO_PATH}\n    include: ['a\\b.md']\n");
        var settings = new SourceSettings(yaml, temp.resolve("root/link/.."), temp, temp);
        var scanned = new YamlKnowledgeSource().scan(settings);
        assertThat(scanned).hasSize(1);
        assertThat(scanned.getFirst().metadata()).containsEntry("source_key", "repo/a\\b.md");
    }

}
