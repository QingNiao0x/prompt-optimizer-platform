package com.promptoptimizer.payment.service;

import com.promptoptimizer.payment.domain.VerifiedRechargePayment;
import com.promptoptimizer.payment.service.PaymentRecordConflictException;
import com.promptoptimizer.payment.infrastructure.RechargeRecordRow;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 仅接收经服务端支付适配器校验的充值事实；不向普通用户暴露伪造已支付状态的写接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class RechargeRecordService {

    private final RechargeRecordMapper mapper;

    public RechargeRecordService(ObjectProvider<RechargeRecordMapper> mapperProvider) {
        this.mapper = mapperProvider.getIfAvailable();
    }

    /** 在事务内幂等保存支付成功事实，返回稳定的充值记录 ID。 */
    @Transactional
    public UUID recordVerifiedPayment(VerifiedRechargePayment payment) {
        if (mapper == null) {
            throw new IllegalStateException("充值记录数据库当前不可用");
        }
        UUID candidateId = UUID.randomUUID();
        mapper.insertIfAbsent(candidateId, payment);
        RechargeRecordRow stored = mapper.selectByProviderTransaction(
                payment.paymentProvider(), payment.providerTransactionId());
        if (stored == null) {
            throw new IllegalStateException("充值流水写入后无法读取");
        }
        if (!stored.matches(payment)) {
            throw new PaymentRecordConflictException();
        }
        return stored.id();
    }
}
