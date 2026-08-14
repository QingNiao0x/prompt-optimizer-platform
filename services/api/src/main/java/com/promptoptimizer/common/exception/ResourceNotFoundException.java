package com.promptoptimizer.common.exception;

/**
 * 请求的资源不存在时抛出，由全局异常处理转换为 404 响应。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
