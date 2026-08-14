package com.promptoptimizer.provider.domain;

/**
 * 模型供应商调用失败的标准分类。
 *
 * <p>该枚举隔离上游供应商的状态码差异，供 Web 层转换为稳定的平台错误码。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum ProviderFailureType {
    AUTHENTICATION,
    RATE_LIMIT,
    TIMEOUT,
    REQUEST_REJECTED,
    UPSTREAM_UNAVAILABLE,
    INVALID_RESPONSE,
    CONFIGURATION,
    INTERNAL
}
