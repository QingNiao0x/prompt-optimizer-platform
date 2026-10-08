package com.promptoptimizer.identity.service;

/**
 * 短信业务的安全错误；message 只允许固定文案，不包含供应商响应或个人标识。
 * @author QingNiao
 * @since 0.1.0
 */
public class SmsException extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfterSeconds;

    /** 创建稳定的业务错误响应。 */
    public SmsException(int status, String code, String message) { this(status, code, message, 0); }

    /** 创建携带服务端重试等待时间的限流错误。 */
    public SmsException(int status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    /** 存储或供应商失败时拒绝继续认证，不输出原始异常内容。 */
    public static SmsException unavailable() {
        return new SmsException(503, "SMS_UNAVAILABLE", "短信验证服务暂时不可用，请稍后重试。");
    }

    /** 不区分未知挑战、用途错误、他人浏览器或已失效挑战。 */
    public static SmsException invalid() {
        return new SmsException(400, "SMS_CHALLENGE_INVALID", "验证码已失效或不适用于本次操作，请重新获取。");
    }
}
