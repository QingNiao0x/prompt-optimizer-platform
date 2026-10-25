package com.promptoptimizer.textpolish.controller;

import com.promptoptimizer.common.api.*;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.textpolish.service.TextPolishException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 转换润色业务固定错误；不捕获无关异常、不向响应泄露正文或上游消息。
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice
@Order(-10)
public class TextPolishExceptionHandler {
    /** 状态码保留缺失、冲突和服务不可用区别。 */
    @ExceptionHandler(TextPolishException.class)
    public ResponseEntity<ApiErrorResponse> handle(TextPolishException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new ApiErrorResponse((String)request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                new ApiError(failure.code(), failure.getMessage(), failure.status() == 503, Map.of())));
    }
}
