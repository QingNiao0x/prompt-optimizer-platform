package com.promptoptimizer.payment.service;

/**
 * 支付渠道重放了已绑定其他用户或金额的交易流水，充值账本必须保持原始记录不变。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class PaymentRecordConflictException extends RuntimeException {

    /** 返回不含支付流水明文的固定冲突消息。 */
    public PaymentRecordConflictException() {
        super("支付流水号已关联到不同的充值信息");
    }
}
