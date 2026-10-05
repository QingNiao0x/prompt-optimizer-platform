```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FormMerge {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "age", "enabled", "region");

    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> candidate, boolean confirmed) {
        Map<String, Object> safeCurrent = current == null ? Map.of() : current;
        Map<String, Object> safeCandidate = candidate == null ? Map.of() : candidate;

        Map<String, Object> result = new HashMap<>(safeCurrent);

        if (!confirmed) {
            return result;
        }

        for (String field : ALLOWED_FIELDS) {
            Object currentValue = safeCurrent.get(field);
            boolean missingOrBlank = currentValue == null || "".equals(currentValue);

            if (missingOrBlank) {
                Object candidateValue = safeCandidate.get(field);
                if (candidateValue != null && !"".equals(candidateValue)) {
                    result.put(field, candidateValue);
                }
            }
        }

        return result;
    }
}
```