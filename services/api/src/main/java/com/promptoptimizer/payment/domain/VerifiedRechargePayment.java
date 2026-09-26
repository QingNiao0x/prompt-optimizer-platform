package com.promptoptimizer.payment.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.time.OffsetDateTime;

/**
 * 支付适配器完成签名与金额核验后交给充值账本的已支付事实，不接受浏览器直接构造。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record VerifiedRechargePayment(
        UUID userId,
        UUID tenantId,
        String planCode,
        String planName,
        long amountMinor,
        String currency,
        String paymentProvider,
        String providerTransactionId,
        OffsetDateTime paidAt
) {

    public VerifiedRechargePayment {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(paidAt, "paidAt must not be null");
        planCode = requireText(planCode, "套餐编码", 40);
        planName = requireText(planName, "套餐名称", 120);
        currency = requireText(currency, "币种", 3).toUpperCase(Locale.ROOT);
        paymentProvider = requireText(paymentProvider, "支付渠道", 40);
        providerTransactionId = requireText(providerTransactionId, "支付流水号", 160);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("币种必须为 3 位 ISO 4217 字母代码");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("充值金额必须大于零，单位为货币最小单位");
        }
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过 " + maxLength + " 个字符");
        }
        return value.trim();
    }
}
