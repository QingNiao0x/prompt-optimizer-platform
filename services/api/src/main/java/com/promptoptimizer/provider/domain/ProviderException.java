package com.promptoptimizer.provider.domain;

import java.util.Objects;

/**
 * 模型供应商适配层抛出的统一异常。
 *
 * <p>异常消息不得包含 API Key、完整上游响应或用户源码，避免日志和接口响应泄露敏感信息。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class ProviderException extends RuntimeException {

    private final ProviderFailureType failureType;
    private final boolean retryable;

    /**
     * 创建不带底层异常的 Provider 异常。
     */
    public ProviderException(
            ProviderFailureType failureType,
            String message,
            boolean retryable
    ) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        this.retryable = retryable;
    }

    /**
     * 创建带底层异常的 Provider 异常。
     */
    public ProviderException(
            ProviderFailureType failureType,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        this.retryable = retryable;
    }

    /**
     * 返回失败分类。
     */
    public ProviderFailureType getFailureType() {
        return failureType;
    }

    /**
     * 返回该失败是否允许重试。
     */
    public boolean isRetryable() {
        return retryable;
    }
}
