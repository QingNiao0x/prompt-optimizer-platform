package com.promptoptimizer.common.exception;

import com.promptoptimizer.common.api.ApiError;
import com.promptoptimizer.common.api.ApiErrorResponse;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.context.service.DocumentUploadException;
import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.provider.infrastructure.concurrency.ModelConcurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * 全局异常处理：把业务异常、校验异常和未知异常转换为统一错误响应。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern JVM_HELPFUL_NULL_POINTER_MESSAGE = Pattern.compile(
            "^Cannot invoke \"[^\"\\r\\n]{1,256}\" because \"[^\"\\r\\n]{1,96}\" is null$"
    );

    /** 本平台的账号并发和总容量不足分别返回 429/503，不混淆为上游模型限流。 */
    @ExceptionHandler(ModelConcurrencyException.class)
    public ResponseEntity<ApiErrorResponse> handleModelConcurrency(ModelConcurrencyException exception,
                                                                   HttpServletRequest request) {
        HttpStatus status = exception.getReason() == ModelConcurrencyException.Reason.USER_LIMIT
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        String code = switch (exception.getReason()) {
            case USER_LIMIT -> "USER_MODEL_CONCURRENCY_LIMIT";
            case GLOBAL_LIMIT -> "MODEL_CONCURRENCY_LIMIT";
            case STORE_UNAVAILABLE -> "MODEL_CONCURRENCY_UNAVAILABLE";
        };
        ResponseEntity<ApiErrorResponse> response = buildResponse(request, status, code,
                exception.getMessage(), true, Map.of("retryAfterSeconds", 1));
        return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, "1").body(response.getBody());
    }

    /** 计划过期时返回冲突状态，提示客户端重新准备上下文和确认问题。 */
    @ExceptionHandler(com.promptoptimizer.enhancement.service.PlanningSessionExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handlePlanningExpiry(
            com.promptoptimizer.enhancement.service.PlanningSessionExpiredException exception,
            HttpServletRequest request) {
        return buildResponse(request, HttpStatus.CONFLICT, "PLANNING_SESSION_EXPIRED",
                exception.getMessage(), false, Map.of());
    }

    /** 共享计划存储不可用时返回可重试错误，避免创建无法跨实例读取的会话。 */
    @ExceptionHandler(com.promptoptimizer.enhancement.service.PlanningStoreUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handlePlanningStoreUnavailable(
            com.promptoptimizer.enhancement.service.PlanningStoreUnavailableException exception,
            HttpServletRequest request) {
        return buildResponse(request, HttpStatus.SERVICE_UNAVAILABLE, "PLANNING_STORE_UNAVAILABLE",
                exception.getMessage(), true, Map.of());
    }

    /** 将注册冲突、验证码错误和限流结果映射为稳定的公开错误。 */
    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ApiErrorResponse> handleRegistrationException(
            RegistrationException exception,
            HttpServletRequest request
    ) {
        RegistrationErrorMapping mapping = mapRegistrationError(exception.getReason());
        Map<String, Object> details = exception.getRetryAfterSeconds() > 0
                ? Map.of("retryAfterSeconds", exception.getRetryAfterSeconds())
                : Map.of();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(mapping.status());
        if (exception.getRetryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
        }
        String requestId = (String) request.getAttribute(
                com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );
        ApiError error = new ApiError(
                mapping.code(),
                exception.getMessage(),
                mapping.retryable(),
                details
        );
        return response.body(new ApiErrorResponse(requestId, error));
    }

    /** 支付渠道流水重复且业务字段不一致时返回冲突，不回显支付流水或订单凭据。 */
    @ExceptionHandler(com.promptoptimizer.payment.service.PaymentRecordConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentRecordConflict(
            com.promptoptimizer.payment.service.PaymentRecordConflictException exception,
            HttpServletRequest request
    ) {
        return buildResponse(request, HttpStatus.CONFLICT, "PAYMENT_RECORD_CONFLICT",
                exception.getMessage(), false, Map.of());
    }

    /** 图形验证码错误为 400，连续失败锁定为 429；存储故障不能误报为用户输错。 */
    @ExceptionHandler(com.promptoptimizer.identity.service.LoginGuardException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginGuard(
            com.promptoptimizer.identity.service.LoginGuardException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.code()) {
            case "LOGIN_LOCKED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "LOGIN_GUARD_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> details = exception.retryAfterSeconds() > 0
                ? Map.of("retryAfterSeconds", exception.retryAfterSeconds())
                : Map.of();
        ResponseEntity<ApiErrorResponse> response = buildResponse(
                request, status, exception.code(), exception.getMessage(),
                status.is5xxServerError() || exception.retryAfterSeconds() > 0, details);
        if (exception.retryAfterSeconds() <= 0) {
            return response;
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(response.getBody());
    }

    /** 区分凭据拒绝和认证服务故障，避免将后端异常伪装成错误密码。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String routeTemplate = route instanceof String value ? value : "<unmapped>";
        Object requestId = request.getAttribute(com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String safeRequestId = LogFields.value(requestId instanceof String value ? value : null);
        String safeMethod = LogFields.value(request.getMethod());
        String safeRoute = LogFields.value(routeTemplate);

        if (exception instanceof AuthenticationServiceException) {
            // Spring wraps failures from UserDetailsService (including persistence failures) in this type.
            // Keep cause classes, SQLState and application frames, but never exception messages or parameters.
            LOGGER.error("event=auth.authentication_service_failure requestId={} method={} route={} causes:\n{}",
                    safeRequestId,
                    safeMethod,
                    safeRoute,
                    safeFailureLocations(exception));
            return buildResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    "AUTHENTICATION_SERVICE_UNAVAILABLE", "登录服务暂时不可用，请稍后重试。", true, Map.of());
        }

        LOGGER.warn("event=auth.authentication_rejected requestId={} method={} route={} failureType={}",
                safeRequestId,
                safeMethod,
                safeRoute,
                LogFields.value(exception.getClass().getSimpleName()));
        return buildResponse(
                request,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "邮箱或密码错误，或当前账户不可用。",
                false,
                Map.of()
        );
    }

    /**
     * 处理跨字段业务校验失败。
     */
    @ExceptionHandler(InvalidOptimizationRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOptimizationRequest(
            InvalidOptimizationRequestException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT",
                exception.getMessage(),
                false,
                Map.of()
        );
    }

    /** 数据库角色被撤销后，管理请求仍应返回明确的 403。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return buildResponse(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "当前用户无权执行该管理操作。", false, Map.of());
    }

    /**
     * 处理模型供应商异常，并映射为稳定的平台错误码。
     */
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderException(
            ProviderException exception,
            HttpServletRequest request
    ) {
        ProviderErrorMapping mapping = mapProviderError(exception.getFailureType());
        Map<String, Object> details = Map.of();
        if (exception instanceof ProviderResponseValidationException invalid) {
            // 仅公开代码定义的枚举、固定字段路径与实际调用次数，便于定位 502；绝不回显失败模型正文或 cause。
            var diagnostics = new LinkedHashMap<String, Object>();
            diagnostics.put("validationReason", invalid.getReason().name());
            diagnostics.put("validationField", invalid.getField());
            if (invalid.getModelAttempts() > 0) {
                diagnostics.put("modelAttempts", invalid.getModelAttempts());
            }
            details = Map.copyOf(diagnostics);
        }
        return buildResponse(
                request,
                mapping.status(),
                mapping.code(),
                mapping.message(),
                exception.isRetryable(),
                details
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
     * 处理加密主密钥未配置的情况，给出可操作的配置提示。
     */
    @ExceptionHandler(EncryptionSecretMissingException.class)
    public ResponseEntity<ApiErrorResponse> handleEncryptionSecretMissing(
            EncryptionSecretMissingException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_CONFIGURATION_ERROR",
                "服务端尚未配置 API Key 加密主密钥，请联系管理员。",
                false,
                Map.of()
        );
    }

    /**
     * 将大型文档上传中的可预期错误转换为稳定状态码，不暴露临时文件路径或异常堆栈。
     */
    @ExceptionHandler(DocumentUploadException.class)
    public ResponseEntity<ApiErrorResponse> handleDocumentUploadException(
            DocumentUploadException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.getReason()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CAPACITY_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return buildResponse(
                request,
                status,
                "DOCUMENT_PROCESSING_ERROR",
                exception.getMessage(),
                exception.getReason() == DocumentUploadException.Reason.CONFLICT
                        || exception.getReason() == DocumentUploadException.Reason.CAPACITY_EXCEEDED,
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
        // 异常消息/完整 Throwable 可能携带上传源码、凭据或上游响应，只记录类型与代码位置。
        // 使用已匹配的路由模板，不能记录可能包含用户数据的 URI、查询串或请求体。
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String routeTemplate = route instanceof String value ? value : "<unmapped>";
        LOGGER.error("请求处理失败, requestId={}, workflowId={}, method={}, route={}, 原因:\n{}",
                request.getAttribute(com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                LogFields.value(org.slf4j.MDC.get("workflowId")),
                LogFields.value(request.getMethod()),
                LogFields.value(routeTemplate),
                safeFailureLocations(exception));
        return buildResponse(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试。",
                true,
                Map.of()
        );
    }

    /** 把查询参数 Bean Validation 失败映射为统一的 400，不回显原始参数值。 */
    @ExceptionHandler({
            jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidQueryParameters(Exception exception,
                                                                          HttpServletRequest request) {
        return buildResponse(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "请求参数不符合要求。", false, Map.of());
    }

    /** UUID 等路径或查询参数类型不合法时返回固定提示，不回显可能包含个人信息的输入值。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameterType(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return buildResponse(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "请求参数格式不正确。", false, Map.of());
    }

    /** 有界地提取异常链及本项目代码位置；不读取异常消息、源码文件路径或 suppressed 信息。 */
    private String safeFailureLocations(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var lines = new ArrayList<String>();
        int causeIndex = 0;
        for (Throwable current = failure; current != null && causeIndex < 6 && seen.add(current);
             current = current.getCause()) {
            String sqlDiagnostics = current instanceof SQLException sqlException
                    ? sqlDiagnostics(sqlException)
                    : "";
            lines.add("cause[" + causeIndex++ + "] " + current.getClass().getName() + sqlDiagnostics);
            String helpfulNullPointerMessage = helpfulNullPointerMessage(current);
            if (helpfulNullPointerMessage != null) {
                lines.add("  detail: " + helpfulNullPointerMessage);
            }
            var frames = Arrays.stream(current.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("com.promptoptimizer."))
                    .limit(8)
                    .map(frame -> "  at " + frame.getClassName() + "." + frame.getMethodName()
                            + "(" + frame.getLineNumber() + ")")
                    .toList();
            lines.addAll(frames);
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** 仅保留 JVM 生成的安全型空指针诊断，不输出任意异常消息或请求数据。 */
    private String helpfulNullPointerMessage(Throwable failure) {
        if (!(failure instanceof NullPointerException)) {
            return null;
        }
        String message = failure.getMessage();
        return message != null && JVM_HELPFUL_NULL_POINTER_MESSAGE.matcher(message).matches()
                ? message
                : null;
    }

    /** 仅输出 JDBC 诊断编号；不记录 SQLException 消息、SQL 参数或连接信息。 */
    private String sqlDiagnostics(SQLException exception) {
        String sqlState = exception.getSQLState();
        String stateField = sqlState != null && sqlState.matches("[0-9A-Z]{5}")
                ? " sqlState=" + sqlState
                : "";
        return stateField + " vendorCode=" + exception.getErrorCode();
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

    /** 把注册业务原因映射为固定的 HTTP 状态和错误码，避免直接暴露内部异常类型。 */
    private RegistrationErrorMapping mapRegistrationError(RegistrationException.Reason reason) {
        return switch (reason) {
            case EMAIL_ALREADY_REGISTERED -> new RegistrationErrorMapping(
                    HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", false
            );
            case RESEND_TOO_SOON -> new RegistrationErrorMapping(
                    HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_CODE_RESEND_TOO_SOON", true
            );
            case EMAIL_RATE_LIMITED -> new RegistrationErrorMapping(
                    HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RATE_LIMITED", true
            );
            case IP_RATE_LIMITED -> new RegistrationErrorMapping(
                    HttpStatus.TOO_MANY_REQUESTS, "IP_RATE_LIMITED", true
            );
            case CODE_INVALID -> new RegistrationErrorMapping(
                    HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_INVALID", false
            );
            case CODE_EXPIRED -> new RegistrationErrorMapping(
                    HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED", false
            );
            case CODE_ATTEMPTS_EXHAUSTED -> new RegistrationErrorMapping(
                    HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_ATTEMPTS_EXHAUSTED", false
            );
            case PASSWORD_INVALID -> new RegistrationErrorMapping(
                    HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", false
            );
            case DELIVERY_UNAVAILABLE -> new RegistrationErrorMapping(
                    HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE", true
            );
            case SERVICE_UNAVAILABLE -> new RegistrationErrorMapping(
                    HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_SERVICE_UNAVAILABLE", true
            );
        };
    }

    /**
     * Provider 错误映射的内部载体。
     */
    private record ProviderErrorMapping(HttpStatus status, String code, String message) {
    }

    private record RegistrationErrorMapping(HttpStatus status, String code, boolean retryable) {
    }
}
