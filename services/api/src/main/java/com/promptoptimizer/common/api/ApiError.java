package com.promptoptimizer.common.api;

import java.util.Map;

/**
 * 统一错误结构，code 为稳定错误码，details 只保存脱敏详情。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ApiError(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details
) {
}
