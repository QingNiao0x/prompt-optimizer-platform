package com.promptoptimizer.payment.infrastructure;

import com.promptoptimizer.payment.domain.VerifiedRechargePayment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * recharge_record 支付事实的 MyBatis 映射行，用于核对渠道流水幂等重放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record RechargeRecordRow(
        UUID id,
        UUID tenantId,
        UUID userId,
        String planCode,
        String planName,
        long amountMinor,
        String currency,
        String paymentProvider,
        String providerTransactionId,
        OffsetDateTime paidAt
) {

    /** 比较支付事实字段；支付时间按同一瞬间比较，兼容时区偏移表示差异。 */
    public boolean matches(VerifiedRechargePayment payment) {
        return tenantId.equals(payment.tenantId())
                && userId.equals(payment.userId())
                && planCode.equals(payment.planCode())
                && planName.equals(payment.planName())
                && amountMinor == payment.amountMinor()
                && currency.equals(payment.currency())
                && paymentProvider.equals(payment.paymentProvider())
                && providerTransactionId.equals(payment.providerTransactionId())
                && paidAt.isEqual(payment.paidAt());
    }
}
