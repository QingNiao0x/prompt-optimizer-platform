```java
public final class DecisionRules {

    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (business == null) {
            throw new IllegalArgumentException("business must not be null");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }

        return switch (business) {
            case "APPROVAL" -> decideApproval(amount, restricted);
            case "REFUND" -> decideRefund(amount, days, paid);
            default -> throw new IllegalArgumentException("business must be APPROVAL or REFUND");
        };
    }

    private static String decideApproval(int amount, boolean restricted) {
        if (restricted) {
            return "DENY";
        }
        return amount <= 1000 ? "AUTO" : "MANUAL";
    }

    private static String decideRefund(int amount, int days, boolean paid) {
        if (!paid || days > 7) {
            return "DENY";
        }
        return amount <= 1000 ? "AUTO" : "MANUAL";
    }
}
```