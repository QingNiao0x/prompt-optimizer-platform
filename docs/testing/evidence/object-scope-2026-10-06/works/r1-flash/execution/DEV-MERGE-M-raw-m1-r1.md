```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FormMerge {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "age", "enabled", "region");

    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> candidate, boolean confirmed) {
        Map<String, Object> currentMap = current == null ? Map.of() : current;
        Map<String, Object> candidateMap = candidate == null ? Map.of() : candidate;

        Map<String, Object> result = new HashMap<>(currentMap);

        if (!confirmed) {
            return result;
        }

        for (String field : ALLOWED_FIELDS) {
            if (shouldFill(result, field)) {
                Object candidateValue = candidateMap.get(field);
                if (candidateValue != null && !(candidateValue instanceof String s && s.isEmpty())) {
                    result.put(field, candidateValue);
                }
            }
        }

        return result;
    }

    private static boolean shouldFill(Map<String, Object> current, String field) {
        if (!current.containsKey(field)) {
            return true;
        }
        Object value = current.get(field);
        if (value == null) {
            return true;
        }
        return value instanceof String s && s.isEmpty();
    }
}
```