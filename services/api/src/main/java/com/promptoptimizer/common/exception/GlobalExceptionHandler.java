package com.promptoptimizer.common.exception;

import com.promptoptimizer.common.api.ApiError;
import com.promptoptimizer.common.api.ApiErrorResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把业务异常、校验异常和未知异常转换为统一错误响应。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理模型供应商异常，并映射为稳定的平台错误码。
     */
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderException(
            ProviderException exception,
            HttpServletRequest request
    ) {
        ProviderErrorMapping mapping = mapProviderError(exception.getFailureType());
        return buildResponse(
                request,
                mapping.status(),
                mapping.code(),
                mapping.message(),
                exception.isRetryable(),
                Map.of()
        );
    }

    /**
     * 处理 Bean Validation 校验失败，返回字段级错误详情。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        details.put("fields", fields);

        return buildResponse(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT",
                "请求参数校验失败。",
                false,
                details
        );
    }

    /**
     * 处理请求体无法解析的情况。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT",
                "请求体格式无效。",
                false,
                Map.of()
        );
    }

    /**
     * 处理资源不存在的情况。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                request,
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                exception.getMessage(),
                false,
                Map.of()
        );
    }

    /**
     * 兜底处理未预期异常，避免向客户端暴露内部堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试。",
                true,
                Map.of()
        );
    }

    /**
     * 构建统一错误响应，并从请求中读取请求标识。
     */
    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details
    ) {
        String requestId = (String) request.getAttribute(com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        ApiError error = new ApiError(code, message, retryable, details);
        return ResponseEntity.status(status).body(new ApiErrorResponse(requestId, error));
    }

    /**
     * 将 Provider 失败类型映射为 HTTP 状态、错误码和用户提示。
     */
    private ProviderErrorMapping mapProviderError(ProviderFailureType failureType) {
        return switch (failureType) {
            case AUTHENTICATION -> new ProviderErrorMapping(
                    HttpStatus.BAD_GATEWAY,
                    "PROVIDER_AUTH_FAILED",
                    "模型服务配置无效，请检查管理员配置。"
            );
            case RATE_LIMIT -> new ProviderErrorMapping(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_RATE_LIMITED",
                    "模型服务当前请求繁忙，请稍后重试。"
            );
            case TIMEOUT -> new ProviderErrorMapping(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "PROVIDER_TIMEOUT",
                    "模型服务响应超时，请稍后重试。"
            );
            case INVALID_RESPONSE -> new ProviderErrorMapping(
                    HttpStatus.BAD_GATEWAY,
                    "RESULT_INVALID",
                    "模型返回结果格式无效，请重试。"
            );
            case REQUEST_REJECTED -> new ProviderErrorMapping(
                    HttpStatus.BAD_GATEWAY,
                    "PROVIDER_REQUEST_REJECTED",
                    "模型服务拒绝了本次请求。"
            );
            case UPSTREAM_UNAVAILABLE -> new ProviderErrorMapping(
                    HttpStatus.BAD_GATEWAY,
                    "PROVIDER_UNAVAILABLE",
                    "模型服务暂时不可用，请稍后重试。"
            );
            case CONFIGURATION, INTERNAL -> new ProviderErrorMapping(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "服务配置异常，请联系管理员。"
            );
        };
    }

    /**
     * Provider 错误映射的内部载体。
     */
    private record ProviderErrorMapping(HttpStatus status, String code, String message) {
    }
}
