package com.promptoptimizer.analytics.service;

/**
 * journal 与数据库均不可用时，浏览器事件未持久接收，返回可重试错误而不是伪造成功。
 * 异常消息固定，不包含磁盘路径、事件明细或底层异常消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class AnalyticsDeliveryUnavailableException extends RuntimeException {
    /** 保留底层原因供安全诊断使用，客户端只接收固定错误码与信息。 */
    public AnalyticsDeliveryUnavailableException(Throwable cause) {
        super("统计事件暂未接收，请稍后重试。", cause);
    }
}
