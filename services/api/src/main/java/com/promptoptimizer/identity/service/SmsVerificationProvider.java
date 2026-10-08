package com.promptoptimizer.identity.service;

/**
 * 云端验证码生成与核验边界；调用方负责用途绑定、限流和一次性消费。
 * @author QingNiao
 * @since 0.1.0
 */
public interface SmsVerificationProvider {
    /** 提交一次发送请求；不得自动重试结果不确定的请求，不返回验证码。 */
    void send(String normalizedPhone, String scheme, String template, String outId);
    /** 仅云端明确 PASS 返回 true；超时及服务失败必须抛出安全错误。 */
    boolean verify(String normalizedPhone, String scheme, String code, String outId);
}
