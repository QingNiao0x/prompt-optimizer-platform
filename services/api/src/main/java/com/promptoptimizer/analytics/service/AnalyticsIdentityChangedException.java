package com.promptoptimizer.analytics.service;

/**
 * 离线事件所属账号与当前登录主体不同，禁止把旧账号操作记到新账号。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class AnalyticsIdentityChangedException extends RuntimeException {
    /** 使用固定信息，不回显客户端或当前账号标识。 */
    public AnalyticsIdentityChangedException() {
        super("登录账号已变化，不能提交其他账号的统计事件。");
    }
}
