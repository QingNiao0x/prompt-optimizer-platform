```java
public final class DecisionRules {
    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (business == null || amount < 0 || days < 0) {
            throw new IllegalArgumentException();
        }

        if ("APPROVAL".equals(business)) {
            if (restricted) {
                return "DENY";
            }
            return amount <= 1000 ? "AUTO" : "MANUAL";
        }

        if ("REFUND".equals(business)) {
            if (!paid || days > 7) {
                return "DENY";
            }
            return amount <= 1000 ? "AUTO" : "MANUAL";
        }

        throw new IllegalArgumentException();
    }
}
```