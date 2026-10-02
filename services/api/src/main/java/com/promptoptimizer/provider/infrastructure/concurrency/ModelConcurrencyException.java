package com.promptoptimizer.provider.infrastructure.concurrency;

/**
 * 本平台的并发拒绝，与供应商返回的 429 区分，避免被结构修复重试重复提交。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class ModelConcurrencyException extends RuntimeException {

    private final Reason reason;

    /** 创建可以安全返回给用户的并发错误，保留内部故障原因供统一日志处理。 */
    public ModelConcurrencyException(Reason reason, int limit, Throwable cause) {
        super(switch (reason) {
            case USER_LIMIT -> "你的账号已有 " + limit + " 个模型相关请求正在处理，请等待其中一个完成后重试。";
            case GLOBAL_LIMIT -> "当前模型服务繁忙，请稍后重试。";
            case STORE_UNAVAILABLE -> "模型并发控制服务暂时不可用，请稍后重试。";
        }, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    /** 账号上限返回 429；平台容量和共享存储不可用返回 503。 */
    public enum Reason { USER_LIMIT, GLOBAL_LIMIT, STORE_UNAVAILABLE }
}
