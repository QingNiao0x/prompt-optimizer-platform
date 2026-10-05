```java
import java.util.HashMap;
import java.util.Map;

public final class FormMerge {

    private static final String[] ALLOWED_FIELDS = {"name", "age", "enabled", "region"};

    private FormMerge() {
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> candidate, boolean confirmed) {
        Map<String, Object> safeCurrent = current == null ? new HashMap<>() : current;
        Map<String, Object> safeCandidate = candidate == null ? new HashMap<>() : candidate;

        Map<String, Object> result = new HashMap<>(safeCurrent);

        if (!confirmed) {
            return result;
        }

        for (String field : ALLOWED_FIELDS) {
            Object currentValue = safeCurrent.get(field);
            boolean currentMissing = currentValue == null || (currentValue instanceof String && ((String) currentValue).isEmpty());

            if (currentMissing) {
                Object candidateValue = safeCandidate.get(field);
                boolean candidateUsable = candidateValue != null && !(candidateValue instanceof String && ((String) candidateValue).isEmpty());

                if (candidateUsable) {
                    result.put(field, candidateValue);
                }
            }
        }

        return result;
    }
}
```