package com.promptoptimizer.payment.mapper;

import com.promptoptimizer.analytics.dto.AnalyticsViews.RechargeMetric;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.payment.domain.VerifiedRechargePayment;
import com.promptoptimizer.payment.infrastructure.RechargeRecordRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 通过 XML 记录已验证的成功支付事实，并为管理员统计提供套餐汇总。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface RechargeRecordMapper {

    /**
     * 按支付渠道与渠道流水唯一键幂等插入一次成功支付。
     */
    int insertIfAbsent(@Param("id") UUID id, @Param("payment") VerifiedRechargePayment payment);

    /**
     * 读取该渠道流水对应的事实行，用于拒绝字段不一致的重放请求。
     */
    RechargeRecordRow selectByProviderTransaction(
            @Param("paymentProvider") String paymentProvider,
            @Param("providerTransactionId") String providerTransactionId
    );

    /**
     * 按支付完成时间、套餐和币种统计已支付充值，沿用仪表盘的账号组合条件。
     * 金额仍以最小货币单位保存和相加，不跨币种换算；仅在支付数据源启用后调用。
     */
    List<RechargeMetric> paidByDay(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);
}
