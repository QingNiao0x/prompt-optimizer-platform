```java
import java.util.HashMap;
import java.util.Map;

public final class FormMerge {
    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> candidate, boolean confirmed) {
        Map<String, Object> safeCurrent = current == null ? Map.of() : current;
        Map<String, Object> safeCandidate = candidate == null ? Map.of() : candidate;

        Map<String, Object> result = new HashMap<>(safeCurrent);

        if (!confirmed) {
            return result;
        }

        for (String key : safeCandidate.keySet()) {
            if (!isAllowed(key)) {
                continue;
            }

            Object candidateValue = safeCandidate.get(key);
            if (candidateValue == null || (candidateValue instanceof String s && s.isEmpty())) {
                continue;
            }

            if (!result.containsKey(key) || result.get(key) == null || isBlankString(result.get(key))) {
                result.put(key, candidateValue);
            }
        }

        return result;
    }

    private static boolean isAllowed(String key) {
        return "name".equals(key) || "age".equals(key) || "enabled".equals(key) || "region".equals(key);
    }

    private static boolean isBlankString(Object value) {
        return value instanceof String s && s.isEmpty();
    }
}
```