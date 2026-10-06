```java
public final class DecisionRules {
    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (business == null) {
            throw new IllegalArgumentException("business must not be null");
        }
        if (!business.equals("APPROVAL") && !business.equals("REFUND")) {
            throw new IllegalArgumentException("business must be APPROVAL or REFUND");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }

        if (business.equals("APPROVAL")) {
            if (restricted) {
                return "DENY";
            }
            return amount <= 1000 ? "AUTO" : "MANUAL";
        }

        // business.equals("REFUND")
        if (!paid || days > 7) {
            return "DENY";
        }
        return amount <= 1000 ? "AUTO" : "MANUAL";
    }
}
```