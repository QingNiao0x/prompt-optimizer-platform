package com.promptoptimizer.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证支付来源事实的必填字段、币种格式和最小货币单位边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class VerifiedRechargePaymentTest {

    @Test
    void normalizesCurrencyAndPreservesVerifiedTransactionFields() {
        VerifiedRechargePayment payment = new VerifiedRechargePayment(
                UUID.randomUUID(), UUID.randomUUID(), "starter", "入门套餐", 1990,
                " cny ", "gateway-x", "trade-unique-1", OffsetDateTime.parse("2026-09-25T10:00:00+08:00")
        );

        assertThat(payment.currency()).isEqualTo("CNY");
        assertThat(payment.amountMinor()).isEqualTo(1990);
    }

    @Test
    void rejectsZeroAmountAndInvalidCurrency() {
        assertThatThrownBy(() -> new VerifiedRechargePayment(
                UUID.randomUUID(), UUID.randomUUID(), "starter", "入门套餐", 0,
                "CNY", "gateway-x", "trade-unique-1", OffsetDateTime.parse("2026-09-25T10:00:00+08:00")
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("大于零");
        assertThatThrownBy(() -> new VerifiedRechargePayment(
                UUID.randomUUID(), UUID.randomUUID(), "starter", "入门套餐", 1990,
                "CN", "gateway-x", "trade-unique-1", OffsetDateTime.parse("2026-09-25T10:00:00+08:00")
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISO 4217");
    }
}
