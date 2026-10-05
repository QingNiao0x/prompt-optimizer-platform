```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FormMerge {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "age", "enabled", "region");

    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current,
                                            Map<String, Object> candidate,
                                            boolean confirmed) {
        Map<String, Object> currentMap = current == null ? Map.of() : current;
        Map<String, Object> candidateMap = candidate == null ? Map.of() : candidate;

        Map<String, Object> result = new HashMap<>(currentMap);

        if (!confirmed) {
            return result;
        }

        for (String field : ALLOWED_FIELDS) {
            if (needsFill(result, field)) {
                Object candidateValue = candidateMap.get(field);
                if (isFillable(candidateValue)) {
                    result.put(field, candidateValue);
                }
            }
        }

        return result;
    }

    private static boolean needsFill(Map<String, Object> map, String field) {
        if (!map.containsKey(field)) {
            return true;
        }
        Object value = map.get(field);
        return value == null || "".equals(value);
    }

    private static boolean isFillable(Object value) {
        return value != null && !"".equals(value);
    }
}
```