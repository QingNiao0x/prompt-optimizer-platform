package com.promptoptimizer.common.exception;

/**
 * 增强请求在跨字段业务校验中不合法。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class InvalidOptimizationRequestException extends RuntimeException {

    public InvalidOptimizationRequestException(String message) {
        super(message);
    }
}
