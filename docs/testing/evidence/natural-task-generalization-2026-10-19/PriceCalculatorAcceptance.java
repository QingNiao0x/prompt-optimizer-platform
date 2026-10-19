import java.math.BigDecimal;

/**
 * 对本批合成定价任务的实际源码作独立行为核对；不修补生成物，不依赖JVM断言开关。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class PriceCalculatorAcceptance {
    private static int cases;

    /** 显式覆盖空值、负数、阈值两侧及舍入；预期值独立列出，不调用被测逻辑推导。 */
    public static void main(String[] args) {
        check("0", false, "0.00");
        check("0", true, "0.00");
        check("99.99", true, "99.99");
        check("100", true, "90.00");
        check("100", false, "100.00");
        check("100.005", true, "90.00");
        check("100.05", true, "90.05");
        check("99.995", true, "100.00");
        check("99.995", false, "100.00");
        check("0.005", false, "0.01");
        check("123.455", true, "111.11");
        check("1000.01", true, "900.01");
        invalid(null, false);
        invalid(null, true);
        invalid(new BigDecimal("-0.001"), false);
        invalid(new BigDecimal("-0.001"), true);
        System.out.println("PASS cases=" + cases);
    }

    private static void check(String value, boolean member, String expected) {
        BigDecimal input = new BigDecimal(value);
        BigDecimal actual = PriceCalculator.payable(input, member);
        if (actual == null || !actual.toPlainString().equals(expected) || actual.scale() != 2
                || !input.toPlainString().equals(value)) throw new AssertionError("Amount or input preservation failure");
        cases++;
    }

    private static void invalid(BigDecimal value, boolean member) {
        try { PriceCalculator.payable(value, member); }
        catch (IllegalArgumentException expected) { cases++; return; }
        throw new AssertionError("Missing IllegalArgumentException");
    }
}
