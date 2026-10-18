import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent checks for the frozen PATCH task; never supplies missing generated implementation or tests. */
public final class PatchIndependentChecks {
    private record Check(String name, boolean passed, String detail) { }
    private interface Action { void run() throws Throwable; }
    private static final List<Check> CORE = new ArrayList<>();
    private static final List<Check> DECLARED = new ArrayList<>();
    private static final Map<String, String> OBSERVATIONS = new LinkedHashMap<>();
    private static final String MARKER = "synthetic-private-patch-marker";
    private static Method merge;
    private static boolean inputMutated;
    private static boolean inputPrinted;

    /** Runs core requirements first; null/empty/nested policies are assessed only when separately declared. */
    public static void main(String[] args) throws Exception {
        Class<?> target = Class.forName(args[0]);
        merge = target.getMethod("merge", Map.class, Map.class);
        var policies = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String[] pair = args[i].split("=", 2);
            policies.put(pair[0], pair[1]);
        }
        check(CORE, "public_static_map_signature", () -> require(Modifier.isPublic(merge.getModifiers())
                && Modifier.isStatic(merge.getModifiers()) && Map.class.isAssignableFrom(merge.getReturnType()), "signature"));
        check(CORE, "non_null_override_and_unpatched_values", () -> {
            Map<String,Object> result = call(map("a", "old", "keep", 7), map("a", "new", "added", 4));
            require(result.equals(map("a", "new", "keep", 7, "added", 4)), "incorrect merge");
        });
        check(CORE, "null_patch_values_do_not_overwrite_or_add", () -> {
            Map<String,Object> result = call(map("a", "old"), map("a", null, "absent", null));
            require(result.equals(map("a", "old")), "null override or inserted null key");
        });
        check(CORE, "zero_is_a_valid_value", () -> require(Integer.valueOf(0).equals(call(map("a", 9), map("a", 0)).get("a")), "zero lost"));
        check(CORE, "false_is_a_valid_value", () -> require(Boolean.FALSE.equals(call(map("a", true), map("a", false)).get("a")), "false lost"));
        check(CORE, "empty_string_is_a_valid_value", () -> require("".equals(call(map("a", "old"), map("a", "")).get("a")), "empty string lost"));
        check(CORE, "result_is_a_distinct_map", () -> {
            var current = map("a", 1); var patch = map("b", 2); var result = call(current, patch);
            require(result != current && result != patch, "input map returned");
        });
        check(CORE, "unmodifiable_inputs_are_not_mutated", () -> {
            var result = call(Collections.unmodifiableMap(map("a", 1)), Collections.unmodifiableMap(map("a", 2)));
            require(Integer.valueOf(2).equals(result.get("a")), "merge of readonly inputs failed");
        });
        check(CORE, "input_keys_and_values_are_not_printed", () -> {
            call(map(MARKER + "-current-key", MARKER + "-current-value"),
                    map(MARKER + "-patch-key", MARKER + "-patch-value"));
            require(!inputPrinted, "input observed on stdout or stderr");
        });
        observe("nullCurrent", null, map("a", 2));
        observe("nullPatch", map("a", 1), null);
        observe("emptyCurrent", map(), map("a", 2));
        observe("emptyPatch", map("a", 1), map());
        observe("nested", map("nested", map("keep", 1, "replace", "old")), map("nested", map("replace", "new", "skip", null)));
        for (var policy : policies.entrySet()) declared(policy.getKey(), policy.getValue());
        check(CORE, "all_invocations_preserve_both_inputs", () -> require(!inputMutated, "input mutation observed"));
        check(CORE, "all_invocations_avoid_input_logging", () -> require(!inputPrinted, "input logging observed"));
        System.out.println("{\"coreChecks\":" + checks(CORE) + ",\"declarationChecks\":" + checks(DECLARED)
                + ",\"observations\":" + object(OBSERVATIONS) + ",\"corePassed\":" + CORE.stream().filter(Check::passed).count()
                + ",\"coreFailed\":" + CORE.stream().filter(c -> !c.passed()).count()
                + ",\"declarationFailed\":" + DECLARED.stream().filter(c -> !c.passed()).count() + "}");
    }

    /** Captures output while invoking only generated merge; snapshots detect mutation even when it throws. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(Map<String, Object> current, Map<String, Object> patch) throws Throwable {
        Object beforeCurrent = snapshot(current), beforePatch = snapshot(patch);
        PrintStream originalOut = System.out, originalErr = System.err;
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream();
        try (var capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
             var capturedErr = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut); System.setErr(capturedErr);
            Object value;
            try { value = merge.invoke(null, current, patch); }
            catch (InvocationTargetException error) { throw error.getCause(); }
            require(value instanceof Map, "merge did not return a map");
            return (Map<String, Object>) value;
        } finally {
            System.setOut(originalOut); System.setErr(originalErr);
            inputMutated |= !java.util.Objects.equals(beforeCurrent, snapshot(current))
                    || !java.util.Objects.equals(beforePatch, snapshot(patch));
            inputPrinted |= output.toString(StandardCharsets.UTF_8).contains(MARKER)
                    || errors.toString(StandardCharsets.UTF_8).contains(MARKER);
        }
    }

    private static void observe(String name, Map<String, Object> current, Map<String, Object> patch) {
        try { OBSERVATIONS.put(name, "RETURNS_MAP_SIZE_" + call(current, patch).size()); }
        catch (Throwable error) { OBSERVATIONS.put(name, "THROWS_" + error.getClass().getSimpleName()); }
    }

    /** The Python runner accepts these modes only when an exact source quotation accompanies the declaration. */
    private static void declared(String name, String mode) {
        check(DECLARED, name + "_" + mode, () -> {
            Map<String,Object> current = name.equals("nullCurrent") ? null : name.equals("emptyCurrent") ? map()
                    : name.equals("nested") ? map("nested", map("keep", 1, "replace", "old")) : map("a", 1);
            Map<String,Object> patch = name.equals("nullPatch") ? null : name.equals("emptyPatch") ? map()
                    : name.equals("nested") ? map("nested", map("replace", "new", "skip", null)) : map("a", 2);
            if (mode.startsWith("REJECT")) {
                try { call(current, patch); }
                catch (Throwable error) {
                    if (mode.contains(":")) require(error.getClass().getSimpleName().equals(mode.split(":", 2)[1]), "declared exception type mismatch");
                    return;
                }
                throw new AssertionError("declared rejection not observed");
            }
            var result = call(current, patch);
            Map<String,Object> expected = switch (name) {
                case "nullCurrent", "emptyCurrent" -> map("a", 2);
                case "nullPatch", "emptyPatch" -> map("a", 1);
                case "nested" -> mode.equals("SHALLOW_REPLACE")
                        ? map("nested", map("replace", "new", "skip", null))
                        : map("nested", map("keep", 1, "replace", "new"));
                default -> throw new AssertionError("unknown declared policy");
            };
            require(result.equals(expected), "declared policy mismatch");
        });
    }

    private static Object snapshot(Object input) {
        if (input instanceof Map<?,?> values) {
            var copy = new LinkedHashMap<Object,Object>(); values.forEach((key,value) -> copy.put(key,snapshot(value))); return copy;
        }
        if (input instanceof List<?> values) return values.stream().map(PatchIndependentChecks::snapshot).toList();
        return input;
    }

    private static Map<String,Object> map(Object... values) {
        var result = new LinkedHashMap<String,Object>();
        for (int i=0;i<values.length;i+=2) result.put((String)values[i], values[i+1]);
        return result;
    }

    private static void check(List<Check> destination, String name, Action action) {
        try { action.run(); destination.add(new Check(name,true,"")); }
        catch (Throwable error) { destination.add(new Check(name,false,error.getClass().getSimpleName())); }
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }

    private static String checks(List<Check> values) {
        return values.stream().map(c -> "{\"name\":" + quote(c.name()) + ",\"passed\":" + c.passed()
                + ",\"detail\":" + quote(c.detail()) + "}").collect(java.util.stream.Collectors.joining(",","[","]"));
    }

    private static String object(Map<String,String> values) {
        return values.entrySet().stream().map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
                .collect(java.util.stream.Collectors.joining(",","{","}"));
    }

    private static String quote(String text) { return "\"" + text.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
}
