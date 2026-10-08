package com.promptoptimizer.identity.controller;

import com.promptoptimizer.common.api.ApiError;
import com.promptoptimizer.common.api.ApiErrorResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.SmsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/**
 * 短信业务错误脱敏映射；旧密码登录的其他异常仍由原全局处理器接收。
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice(assignableTypes = {PhoneAuthenticationController.class, AuthenticationController.class})
@Order(-10)
public class SmsExceptionHandler {
    /** 对外错误只包含固定文案、稳定代码与服务端冷却时间。 */
    @ExceptionHandler(SmsException.class)
    public ResponseEntity<ApiErrorResponse> sms(SmsException exception, HttpServletRequest request) {
        var response = ResponseEntity.status(exception.getStatus());
        if (exception.getRetryAfterSeconds() > 0) response.header("Retry-After", Long.toString(exception.getRetryAfterSeconds()));
        return response.body(new ApiErrorResponse((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                new ApiError(exception.getCode(), exception.getMessage(), exception.getStatus() >= 500 || exception.getStatus() == 429,
                        Map.of("retryAfterSeconds", exception.getRetryAfterSeconds()))));
    }

}
