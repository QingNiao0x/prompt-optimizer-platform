package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.api.*;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.imageprompt.service.ImagePromptException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 图像业务异常按现有响应信封返回，避免全局处理器把预期过期或修订冲突映射为内部错误。
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice
@Order(-10)
public class ImagePromptExceptionHandler {
    /** 只转换明确图像业务异常，不吞掉不可预期异常的原始归因。 */
    @ExceptionHandler(ImagePromptException.class)
    public ResponseEntity<ApiErrorResponse> handle(ImagePromptException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new ApiErrorResponse(
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                new ApiError(failure.code(), failure.getMessage(), failure.status() == 503, Map.of())));
    }
}
