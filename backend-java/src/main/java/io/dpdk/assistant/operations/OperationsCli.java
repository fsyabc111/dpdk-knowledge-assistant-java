package io.dpdk.assistant.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 纯解析在业务容器启动前执行；退出码 0=成功、2=参数错误、1=操作失败。 */
public final class OperationsCli {
    private OperationsCli() { }
    public record Command(String name, Map<String, List<String>> options, boolean apply) { }
    public static final String HELP = """
            Java 独立运维 CLI（写操作默认 dry-run，只有 --apply 才写入）
            用法：scripts-java/operations.sh <命令> [参数]
              create-users --id U00000000001 [--id ...] [--roles viewer,teacher] [--daily-quota N] [--apply]
              purge-sessions [--retention-days 30] [--apply]
              purge-task-inputs [--apply]
              retain-issues [--retention-days 30] [--apply]
              ingest --manifest /绝对路径/manifest.json [--apply]
              rollback --release RELEASE [--apply]
              audit-grading-jobs [--limit 200]
              retrieve --query TEXT [--top-k 5]
              judge --cases /绝对路径/cases.json
              help | --help
            judge 仅消费离线 fixture；不调用模型或 harness。错误输出不含凭据/输入正文。
            """;
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "create-users", Set.of("id", "roles", "daily-quota", "apply"),
            "purge-sessions", Set.of("retention-days", "apply"),
            "purge-task-inputs", Set.of("apply"),
            "retain-issues", Set.of("retention-days", "apply"),
            "ingest", Set.of("manifest", "apply"), "rollback", Set.of("release", "apply"),
            "audit-grading-jobs", Set.of("limit"), "retrieve", Set.of("query", "top-k"),
            "judge", Set.of("cases"));

    public static Command parse(String... args) {
        if (args.length == 0 || (args.length == 1 && Set.of("help", "--help", "-h").contains(args[0])))
            return new Command("help", Map.of(), false);
        String name = args[0];
        Set<String> allowed = OPTIONS.get(name);
        if (allowed == null) throw new IllegalArgumentException("未知命令；使用 --help 查看用法");
        Map<String, List<String>> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("参数必须以 -- 开头");
            String key = args[i].substring(2);
            if (!allowed.contains(key)) throw new IllegalArgumentException("当前命令不支持该选项");
            if (options.containsKey(key) && !key.equals("id")) throw new IllegalArgumentException("选项不能重复");
            String value = "true";
            if (!key.equals("apply")) {
                if (++i >= args.length || args[i].startsWith("--") || args[i].isBlank())
                    throw new IllegalArgumentException("选项缺少值");
                value = args[i];
            }
            options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        Command c = new Command(name, options, options.containsKey("apply"));
        switch (name) {
            case "create-users" -> {
                required(c, "id");
                for (String id : options.get("id")) if (!id.matches("U\\d{11}"))
                    throw new IllegalArgumentException("用户 ID 格式必须为 U 加 11 位数字");
                List<String> roles = roles(c);
                if (roles.isEmpty() || !Set.of("viewer", "teacher", "leader").containsAll(roles))
                    throw new IllegalArgumentException("roles 必须为 viewer/teacher/leader 的非空子集");
                if (options.containsKey("daily-quota")) number(c, "daily-quota", 0, Integer.MAX_VALUE, 0);
            }
            case "purge-sessions", "retain-issues" -> number(c, "retention-days", 1, 365000, 30);
            case "audit-grading-jobs" -> number(c, "limit", 1, 10000, 200);
            case "retrieve" -> { required(c, "query"); number(c, "top-k", 1, 100, 5); }
            case "ingest" -> absolute(c, "manifest");
            case "judge" -> absolute(c, "cases");
            case "rollback" -> required(c, "release");
            default -> { }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        options.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return new Command(name, Map.copyOf(frozen), c.apply());
    }
    private static String value(Command c, String key, String fallback) {
        return c.options().getOrDefault(key, List.of(fallback)).get(0);
    }
    private static String required(Command c, String key) {
        if (!c.options().containsKey(key)) throw new IllegalArgumentException("缺少必需选项 --" + key);
        return value(c, key, "");
    }
    private static Path absolute(Command c, String key) {
        Path p = Path.of(required(c, key));
        if (!p.isAbsolute()) throw new IllegalArgumentException("文件参数必须为显式绝对路径");
        return p;
    }
    private static int number(Command c, String key, int min, int max, int fallback) {
        try {
            int n = Integer.parseInt(value(c, key, Integer.toString(fallback)));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("数字选项超出允许范围"); }
    }
    private static List<String> roles(Command c) {
        return Arrays.stream(value(c, "roles", "viewer").split(",", -1)).map(String::trim).distinct().toList();
    }
    public static Map<String, Object> execute(Command c, OperationsPort port) {
        return switch (c.name()) {
            case "create-users" -> port.createUsers(new OperationsPort.CreateUsers(c.options().get("id"), roles(c),
                    c.options().containsKey("daily-quota") ? number(c, "daily-quota", 0, Integer.MAX_VALUE, 0) : null), c.apply());
            case "purge-sessions" -> port.purgeSessions(number(c, "retention-days", 1, 365000, 30), c.apply());
            case "purge-task-inputs" -> port.purgeTaskInputs(c.apply());
            case "retain-issues" -> port.retainIssues(number(c, "retention-days", 1, 365000, 30), c.apply());
            case "ingest" -> port.ingest(absolute(c, "manifest"), c.apply());
            case "rollback" -> port.rollback(required(c, "release"), c.apply());
            case "audit-grading-jobs" -> port.auditGradingJobs(number(c, "limit", 1, 10000, 200));
            case "retrieve" -> port.retrieve(required(c, "query"), number(c, "top-k", 1, 100, 5));
            case "judge" -> port.judge(absolute(c, "cases"));
            default -> throw new IllegalArgumentException("帮助命令不访问业务端口");
        };
    }
    public static int run(String[] args, Supplier<OperationsPort> port, PrintWriter out, PrintWriter err) {
        final Command c;
        try { c = parse(args); }
        catch (IllegalArgumentException e) { err.println(e.getMessage()); err.flush(); return 2; }
        if (c.name().equals("help")) { out.print(HELP); out.flush(); return 0; }
        try {
            Map<String, Object> result = execute(c, port.get());
            out.println(new ObjectMapper().writeValueAsString(Map.of("command", c.name(), "apply", c.apply(), "result", result)));
            out.flush(); return 0;
        } catch (Exception e) {
            err.println("操作失败（" + e.getClass().getSimpleName() + "）；检查数据库身份、配置及业务前置条件");
            err.flush(); return 1;
        }
    }
}
