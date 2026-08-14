package com.promptoptimizer.common.api;

/**
 * 统一错误响应包装，requestId 用于与服务端日志关联。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ApiErrorResponse(String requestId, ApiError error) {
}
