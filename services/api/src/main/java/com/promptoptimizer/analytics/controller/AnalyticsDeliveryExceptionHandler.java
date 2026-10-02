package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.service.AnalyticsDeliveryUnavailableException;
import com.promptoptimizer.analytics.service.AnalyticsIdentityChangedException;
import com.promptoptimizer.common.api.ApiError;
import com.promptoptimizer.common.api.ApiErrorResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 只转换遥测可靠接收与账号变更错误，避免改动共享异常处理器的其他业务响应。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Order(-10)
@RestControllerAdvice(assignableTypes = AnalyticsEventController.class)
public class AnalyticsDeliveryExceptionHandler {
    /** 可靠接收失败返回 503，浏览器必须保留原 eventId 等待恢复，不得生成新事件重试。 */
    @ExceptionHandler(AnalyticsDeliveryUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> unavailable(AnalyticsDeliveryUnavailableException failure,
                                                        HttpServletRequest request) {
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_DELIVERY_UNAVAILABLE", failure.getMessage(), true);
    }

    /** 账号失配属于可辨识冲突；不返回其他账号资料，也不替换事件归属。 */
    @ExceptionHandler(AnalyticsIdentityChangedException.class)
    public ResponseEntity<ApiErrorResponse> identityChanged(AnalyticsIdentityChangedException failure,
                                                           HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "ANALYTICS_IDENTITY_CHANGED", failure.getMessage(), false);
    }

    /** 沿用统一错误结构，仅提供固定信息及服务端 requestId。 */
    private ResponseEntity<ApiErrorResponse> error(HttpServletRequest request, HttpStatus status,
                                                  String code, String message, boolean retryable) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                new ApiError(code, message, retryable, Map.of())));
    }
}
