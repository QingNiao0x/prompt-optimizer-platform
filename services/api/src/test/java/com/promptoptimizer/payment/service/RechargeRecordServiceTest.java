package com.promptoptimizer.payment.service;

import com.promptoptimizer.payment.domain.VerifiedRechargePayment;
import com.promptoptimizer.payment.infrastructure.RechargeRecordRow;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证充值事实的流水幂等性、冲突校验和读取异常处理。 */
class RechargeRecordServiceTest {

    private final RechargeRecordMapper mapper = mock(RechargeRecordMapper.class);
    private final RechargeRecordService service = new RechargeRecordService(provider(mapper));
    private final VerifiedRechargePayment payment = new VerifiedRechargePayment(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "BASIC_MONTHLY", "基础月包", 990, "cny", "provider", "transaction-1",
            OffsetDateTime.parse("2026-09-25T10:00:00+08:00"));

    @Test
    void returnsStableIdForVerifiedPaymentAndMatchingReplay() {
        UUID stableId = UUID.randomUUID();
        when(mapper.selectByProviderTransaction("provider", "transaction-1"))
                .thenReturn(row(stableId, payment));

        UUID result = service.recordVerifiedPayment(payment);

        assertThat(result).isEqualTo(stableId);
        verify(mapper).insertIfAbsent(any(UUID.class), eq(payment));
    }

    @Test
    void rejectsPaymentReplayWithConflictingFacts() {
        VerifiedRechargePayment conflicting = new VerifiedRechargePayment(
                payment.userId(), payment.tenantId(), payment.planCode(), payment.planName(),
                payment.amountMinor() + 1, payment.currency(), payment.paymentProvider(),
                payment.providerTransactionId(), payment.paidAt());
        when(mapper.selectByProviderTransaction("provider", "transaction-1"))
                .thenReturn(row(UUID.randomUUID(), conflicting));

        assertThatThrownBy(() -> service.recordVerifiedPayment(payment))
                .isInstanceOf(PaymentRecordConflictException.class);
    }

    @Test
    void reportsMissingStoredPaymentInsteadOfInventingSuccess() {
        when(mapper.selectByProviderTransaction("provider", "transaction-1")).thenReturn(null);

        assertThatThrownBy(() -> service.recordVerifiedPayment(payment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("充值流水写入后无法读取");
    }

    private RechargeRecordRow row(UUID id, VerifiedRechargePayment value) {
        return new RechargeRecordRow(id, value.tenantId(), value.userId(), value.planCode(), value.planName(),
                value.amountMinor(), value.currency(), value.paymentProvider(),
                value.providerTransactionId(), value.paidAt());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
