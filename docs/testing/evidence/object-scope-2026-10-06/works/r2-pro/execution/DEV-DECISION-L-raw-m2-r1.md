```java
public final class DecisionRules {
    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (business == null || (!business.equals("APPROVAL") && !business.equals("REFUND"))) {
            throw new IllegalArgumentException("Invalid business type");
        }
        if (amount < 0 || days < 0) {
            throw new IllegalArgumentException("Amount and days must be non-negative");
        }

        if (business.equals("APPROVAL")) {
            if (restricted) {
                return "DENY";
            }
            return amount <= 1000 ? "AUTO" : "MANUAL";
        } else {
            if (!paid || days > 7) {
                return "DENY";
            }
            return amount <= 1000 ? "AUTO" : "MANUAL";
        }
    }
}
```