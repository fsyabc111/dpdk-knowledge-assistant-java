package io.dpdk.assistant.session;

import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * FastAPI/Pydantic v2 风格的查询参数校验（J2-05 范围；session/usage/grading 的
 * GET 端点共用）。行为按冻结源路由声明的 Query(...) 约束固化：
 *
 * - int 参数：非整数 → int_parsing；&lt; ge → greater_than_equal（ctx.ge）；
 *   &gt; le → less_than_equal（ctx.le）；
 * - bool 参数：复刻 pydantic bool 解析（true/false/1/0/yes/no/on/off，大小写不敏感），
 *   其余 → bool_parsing；
 * - 可选 string 参数：仅声明 max_length 时超长 → string_too_long（ctx.max_length）。
 *
 * 校验失败抛 422，detail 为与 PydanticBody（auth 包，J2-01 探针固化）同形的数组；
 * loc 首元素为 "query"。求值序（FastAPI solve_dependencies）：Depends 子依赖
 * （认证 401）先于参数校验 —— 调用方须先 authenticate 再调用本校验；
 * internal 维护端点无 Depends，查询参数 422 先于 key 校验（403/503）。
 */
public final class PydanticQuery {

    private PydanticQuery() {
    }

    /* ------------------------------------------------------------------ */
    /* int                                                                 */
    /* ------------------------------------------------------------------ */

    /** 可选 int（Optional[...] = Query(default=None)）：raw 为 null → null，否则解析并校验。 */
    public static Integer optionalIntParam(String name, String raw, Integer ge, Integer le) {
        if (raw == null) {
            return null;
        }
        return intParam(name, raw, null, ge, le);
    }

    /** 必填 int（带原始串渲染）；raw 为 null 视为缺省（调用方处理 default）。 */
    public static int intParam(String name, String raw, Integer defaultValue, Integer ge, Integer le) {
        if (raw == null) {
            if (defaultValue == null) {
                throw invalid(List.of(errorEntry("missing", loc(name), "Field required", null, null)));
            }
            return checkRange(name, defaultValue, defaultValue.toString(), ge, le);
        }
        Integer parsed;
        try {
            parsed = Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(List.of(errorEntry("int_parsing", loc(name),
                    "Input should be a valid integer, unable to parse string as an integer", raw, null)));
        }
        return checkRange(name, parsed, raw, ge, le);
    }

    private static int checkRange(String name, int value, String raw, Integer ge, Integer le) {
        if (ge != null && value < ge) {
            throw invalid(List.of(errorEntry("greater_than_equal", loc(name),
                    "Input should be greater than or equal to " + ge, raw, Map.of("ge", ge))));
        }
        if (le != null && value > le) {
            throw invalid(List.of(errorEntry("less_than_equal", loc(name),
                    "Input should be less than or equal to " + le, raw, Map.of("le", le))));
        }
        return value;
    }

    /* ------------------------------------------------------------------ */
    /* bool                                                                */
    /* ------------------------------------------------------------------ */

    private static final Set<String> TRUE_WORDS = Set.of("true", "1", "on", "yes");
    private static final Set<String> FALSE_WORDS = Set.of("false", "0", "off", "no");

    public static boolean boolParam(String name, String raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase();
        if (TRUE_WORDS.contains(normalized)) {
            return true;
        }
        if (FALSE_WORDS.contains(normalized)) {
            return false;
        }
        throw invalid(List.of(errorEntry("bool_parsing", loc(name),
                "Input should be a valid boolean, unable to interpret input", raw, null)));
    }

    /* ------------------------------------------------------------------ */
    /* string                                                              */
    /* ------------------------------------------------------------------ */

    /** 可选 string（null → null）；声明 max_length 时按码点数校验。 */
    public static String stringParam(String name, String raw, Integer maxLength) {
        if (raw == null) {
            return null;
        }
        if (maxLength != null && raw.codePointCount(0, raw.length()) > maxLength) {
            throw invalid(List.of(errorEntry("string_too_long", loc(name),
                    "String should have at most " + maxLength + " character"
                            + (maxLength == 1 ? "" : "s"),
                    raw, Map.of("max_length", maxLength))));
        }
        return raw;
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private static List<String> loc(String name) {
        return List.of("query", name);
    }

    private static Map<String, Object> errorEntry(String type, List<String> loc, String msg,
                                                  Object input, Map<String, ?> ctx) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("loc", loc);
        entry.put("msg", msg);
        entry.put("input", input);
        if (ctx != null) {
            entry.put("ctx", ctx);
        }
        return entry;
    }

    private static ApiDetailException invalid(List<Map<String, Object>> errors) {
        return new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY, new ArrayList<>(errors));
    }
}
