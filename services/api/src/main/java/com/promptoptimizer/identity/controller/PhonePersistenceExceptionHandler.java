package com.promptoptimizer.identity.controller;

import com.promptoptimizer.common.api.ApiErrorResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.SmsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 仅手机新接口的数据库脱敏映射；不抢占旧密码登录的 AuthenticationServiceException 契约。
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice(assignableTypes = PhoneAuthenticationController.class)
@Order(-9)
public class PhonePersistenceExceptionHandler {
    private final SmsExceptionHandler errors;
    public PhonePersistenceExceptionHandler(SmsExceptionHandler errors) { this.errors = errors; }

    /** 日志保留类型和请求标识，不输出SQL绑定值或完整数据库异常。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> persistence(DataAccessException exception, HttpServletRequest request) {
        LoggerFactory.getLogger(PhonePersistenceExceptionHandler.class).error("event=auth.phone.persistence_failure requestId={} type={}",
                request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), exception.getClass().getSimpleName());
        return errors.sms(SmsException.unavailable(), request);
    }
}
