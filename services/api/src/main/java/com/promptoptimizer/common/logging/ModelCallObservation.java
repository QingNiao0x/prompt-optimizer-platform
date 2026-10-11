package com.promptoptimizer.common.logging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次模型调用尝试的白名单快照。只保留关联号、公开来源、固定状态和数字用量；
 * 不包含用户标识、正文、端点、凭据或原始异常消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ModelCallObservation(
        UUID eventId, OffsetDateTime startedAt, String requestId, String workflowId,
        String operation, String providerRoute, String model, int attempt, boolean mock,
        boolean successful, String failureType, Integer upstreamStatus, boolean willRetry,
        String transportState, long durationMs, ModelCallLogger.TokenUsage usage
) {
    private static final Set<String> OPERATIONS = Set.of("prompt.optimize", "plan.generate",
            "document.summary.map", "document.summary.reduce", "context.embedding");
    private static final Set<String> FAILURES = Set.of("AUTHENTICATION", "RATE_LIMIT", "TIMEOUT",
            "REQUEST_REJECTED", "UPSTREAM_UNAVAILABLE", "INVALID_RESPONSE", "CONFIGURATION",
            "INTERNAL", "RESOURCE_ACCESS", "UNEXPECTED", "MODEL_CONCURRENCY_LIMIT");

    /** 固定代码采用白名单；外部请求关联号只保留 UUID 或不可逆指纹，未知值不进入明细。 */
    public ModelCallObservation {
        Objects.requireNonNull(eventId, "model event id");
        startedAt = Objects.requireNonNull(startedAt, "started at").withOffsetSameInstant(ZoneOffset.UTC);
        requestId = safeRequestId(requestId);
        workflowId = workflowId != null && workflowId.matches("[a-f0-9]{20}") ? workflowId : null;
        operation = OPERATIONS.contains(operation == null ? "" : operation) ? operation : "unknown";
        providerRoute = publicCode(providerRoute);
        model = publicCode(model);
        if (attempt < 1 || attempt > 100) throw new IllegalArgumentException("model attempt out of bounds");
        failureType = successful ? null : (FAILURES.contains(failureType == null ? "" : failureType) ? failureType : "UNEXPECTED");
        upstreamStatus = upstreamStatus != null && upstreamStatus >= 100 && upstreamStatus <= 599 ? upstreamStatus : null;
        transportState = mock ? "MOCK" : (Set.of("NOT_SENT", "STARTED", "RESPONSE_RECEIVED", "UNKNOWN")
                .contains(transportState == null ? "" : transportState) ? transportState : "UNKNOWN");
        durationMs = Math.max(0, durationMs);
    }

    /** 可供查询参数复用的安全请求关联号，不把客户端任意文本持久化为标签。 */
    public static String safeRequestId(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.matches("hash-[a-f0-9]{20}")) return value;
        if (value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        return "hash-" + LogCorrelation.fingerprint("model-request", value);
    }

    /** 来源只能是服务端配置的公开标识；拒绝多行、URL、邮箱及携带凭据的字段。 */
    private static String publicCode(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9._:/-]+")
                && !value.contains("://") && !"[REDACTED]".equals(LogFields.value(value)) ? value : "unknown";
    }
}
