```java
public final class DecisionRules {

    private static final String APPROVAL = "APPROVAL";
    private static final String REFUND = "REFUND";

    private static final String AUTO = "AUTO";
    private static final String MANUAL = "MANUAL";
    private static final String DENY = "DENY";

    private DecisionRules() {
    }

    public static String decide(String business, int amount, int days, boolean paid, boolean restricted) {
        if (business == null || !(APPROVAL.equals(business) || REFUND.equals(business))) {
            throw new IllegalArgumentException("business must be APPROVAL or REFUND");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }

        if (APPROVAL.equals(business)) {
            return decideApproval(amount, restricted);
        }
        return decideRefund(amount, days, paid);
    }

    private static String decideApproval(int amount, boolean restricted) {
        if (restricted) {
            return DENY;
        }
        if (amount <= 1000) {
            return AUTO;
        }
        return MANUAL;
    }

    private static String decideRefund(int amount, int days, boolean paid) {
        if (!paid || days > 7) {
            return DENY;
        }
        if (amount <= 1000) {
            return AUTO;
        }
        return MANUAL;
    }
}
```