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
        Map<String, Object> source = current == null ? Map.of() : current;
        Map<String, Object> result = new HashMap<>(source);

        if (!confirmed) {
            return result;
        }

        Map<String, Object> candidateSource = candidate == null ? Map.of() : candidate;

        for (String field : ALLOWED_FIELDS) {
            if (isMissingOrEmpty(result.get(field)) && result.containsKey(field) || isMissingOrEmpty(result.get(field))) {
                if (candidateSource.containsKey(field)) {
                    Object candidateValue = candidateSource.get(field);
                    if (candidateValue != null && !(candidateValue instanceof String s && s.isEmpty())) {
                        result.put(field, candidateValue);
                    }
                }
            }
        }

        return result;
    }

    private static boolean isMissingOrEmpty(Object value) {
        if (value == null) {
            return true;
        }
        return value instanceof String s && s.isEmpty();
    }
}
```