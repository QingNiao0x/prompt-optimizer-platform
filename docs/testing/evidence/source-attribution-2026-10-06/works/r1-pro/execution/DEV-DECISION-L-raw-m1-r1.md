```java
public final class DecisionRules {

    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (amount < 0 || days < 0) {
            throw new IllegalArgumentException("amount and days must be non-negative");
        }
        if (business == null || (!business.equals("APPROVAL") && !business.equals("REFUND"))) {
            throw new IllegalArgumentException("business must be APPROVAL or REFUND");
        }

        if (business.equals("APPROVAL")) {
            return decideApproval(amount, restricted);
        }
        return decideRefund(amount, days, paid);
    }

    private static String decideApproval(int amount, boolean restricted) {
        if (restricted) {
            return "DENY";
        }
        if (amount <= 1000) {
            return "AUTO";
        }
        return "MANUAL";
    }

    private static String decideRefund(int amount, int days, boolean paid) {
        if (!paid || days > 7) {
            return "DENY";
        }
        if (amount <= 1000) {
            return "AUTO";
        }
        return "MANUAL";
    }
}
```