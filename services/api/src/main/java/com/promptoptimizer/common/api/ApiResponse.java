package com.promptoptimizer.common.api;

/**
 * 统一成功响应包装。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ApiResponse<T>(String requestId, T data) {

    /**
     * 构造成功响应。
     */
    public static <T> ApiResponse<T> success(String requestId, T data) {
        return new ApiResponse<>(requestId, data);
    }
}
