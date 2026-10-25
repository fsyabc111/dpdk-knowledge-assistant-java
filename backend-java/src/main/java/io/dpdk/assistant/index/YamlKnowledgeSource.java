package io.dpdk.assistant.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 精确 include、realpath 边界、按最长 root 归属的固定源扫描器。 */
public final class YamlKnowledgeSource {
    private static final Set<String> EXTENSIONS = Set.of("md", "c", "h", "cpp", "hpp", "py", "js", "jsx", "ts", "tsx", "yml", "yaml");
    private record Source(String name, Path root, List<String> includes) {}
    public record Document(String content, Map<String, Object> metadata) {}

    public List<Document> scan(SourceSettings settings) {
        try {
            List<Source> sources = config(settings);
            Set<Path> files = new TreeSet<>(YamlKnowledgeSource::comparePaths);
            for (Source source : sources) {
                for (String include : source.includes()) {
                    Path path = source.root().resolve(include).toRealPath();
                    confined(source.root(), path);
                    if (Files.isRegularFile(path) && supported(path)) files.add(path);
                    else if (Files.isDirectory(path)) {
                        try (var walk = Files.walk(path)) {
                            for (Path child : walk.filter(Files::isRegularFile).filter(YamlKnowledgeSource::supported).toList()) {
                                Path resolved = child.toRealPath();
                                confined(source.root(), resolved);
                                files.add(resolved);
                            }
                        }
                    } else throw new IllegalArgumentException("Knowledge source path not found: " + path);
                }
            }
            List<Document> result = new ArrayList<>();
            BoundedLinesChunker chunker = new BoundedLinesChunker();
            for (Path file : files) {
                Source owner = null;
                for (Source candidate : sources) {
                    if (file.startsWith(candidate.root()) && (owner == null || candidate.root().getNameCount() > owner.root().getNameCount())) owner = candidate;
                }
                if (owner == null) throw new IllegalStateException("知识源归属丢失");
                String relative = owner.root().relativize(file).toString().replace('\\', '/');
                List<BoundedLinesChunker.Chunk> chunks = chunker.chunk(Files.readString(file, StandardCharsets.UTF_8), relative);
                for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                    var chunk = chunks.get(ordinal);
                    Map<String, Object> meta = new LinkedHashMap<>(chunk.metadata());
                    meta.put("source_key", owner.name() + "/" + relative);
                    meta.put("source_path", relative);
                    meta.put("section", chunk.section());
                    meta.put("title", chunk.section().isEmpty() ? relative : chunk.section());
                    meta.put("ordinal", ordinal);
                    result.add(new Document(chunk.content(), meta));
                }
            }
            return List.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalArgumentException("知识源读取失败", failure);
        }
    }

    private List<Source> config(SourceSettings settings) throws IOException {
        String raw = Files.readString(settings.knowledgeConfig(), StandardCharsets.UTF_8)
                .replace("${DPDK_REPO_PATH}", settings.dpdkRoot().toString())
                .replace("${ASSISTANT_PROJECT_PATH}", settings.assistantRoot().toString());
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(raw);
        if (loaded == null) return List.of();
        if (!(loaded instanceof Map<?, ?> config)) throw new IllegalArgumentException("知识源配置必须是对象");
        Object entries = config.get("sources");
        if (entries == null) return List.of();
        if (!(entries instanceof List<?> list)) throw new IllegalArgumentException("sources 必须是列表");
        List<Source> sources = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source) || !(source.get("root") instanceof String root)
                    || !(source.get("name") instanceof String name)) throw new IllegalArgumentException("知识源缺少 name/root");
            if (root.equals("~") || root.startsWith("~/")) root = System.getProperty("user.home") + root.substring(1);
            Path path = Path.of(root);
            if (!path.isAbsolute()) path = settings.workingDirectory().resolve(path);
            // root 可不存在但 include 不能为空时会在逐项读取处硬失败，沿用 pathlib.resolve 的非严格 root。
            path = realOrNormalized(path);
            List<String> includes = new ArrayList<>();
            Object include = source.get("include");
            if (include != null) {
                if (!(include instanceof List<?> paths)) throw new IllegalArgumentException("include 必须是列表");
                for (Object value : paths) {
                    if (!(value instanceof String text)) throw new IllegalArgumentException("include 项必须是路径字符串");
                    includes.add(text);
                }
            }
            sources.add(new Source(name, path, List.copyOf(includes)));
        }
        return sources;
    }

    private static Path realOrNormalized(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        if (absolute.getParent() == null) return absolute;
        return realOrNormalized(absolute.getParent()).resolve(absolute.getFileName());
    }
    private static boolean supported(Path path) {
        return EXTENSIONS.contains(BoundedLinesChunker.extension(path.toString()).toLowerCase(java.util.Locale.ROOT));
    }
    private static void confined(Path root, Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("知识源路径越界: " + path);
    }
    private static int comparePaths(Path left, Path right) {
        int count = Math.min(left.getNameCount(), right.getNameCount());
        for (int i = 0; i < count; i++) {
            int diff = BoundedLinesChunker.compareCodePoints(left.getName(i).toString(), right.getName(i).toString());
            if (diff != 0) return diff;
        }
        return Integer.compare(left.getNameCount(), right.getNameCount());
    }
}
