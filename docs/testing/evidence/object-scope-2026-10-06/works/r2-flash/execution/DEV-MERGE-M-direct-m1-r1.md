```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FormMerge {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "age", "enabled", "region");

    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> candidate, boolean confirmed) {
        Map<String, Object> result = new HashMap<>();
        if (current != null) {
            result.putAll(current);
        }

        if (!confirmed) {
            return result;
        }

        if (candidate == null) {
            return result;
        }

        for (String field : ALLOWED_FIELDS) {
            if (isMissing(result, field)) {
                Object candidateValue = candidate.get(field);
                if (candidateValue != null && !(candidateValue instanceof String s && s.isEmpty())) {
                    result.put(field, candidateValue);
                }
            }
        }

        return result;
    }

    private static boolean isMissing(Map<String, Object> map, String field) {
        if (!map.containsKey(field)) {
            return true;
        }
        Object value = map.get(field);
        if (value == null) {
            return true;
        }
        return value instanceof String s && s.isEmpty();
    }
}
```