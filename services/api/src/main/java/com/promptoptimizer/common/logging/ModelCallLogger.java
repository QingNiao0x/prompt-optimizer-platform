package com.promptoptimizer.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 统一记录模型调用的安全元数据，不接收或输出提示词、文件内容、凭据及上游响应正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelCallLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCallLogger.class);
    private static final ThreadLocal<Attempt> CURRENT_ATTEMPT = new ThreadLocal<>();

    private ModelCallLogger() {
    }

    /** 记录一次成功的模型调用及服务端解析后的公开模型标识。 */
    public static void completed(
            String operation,
            String providerRoute,
            String model,
            String selectionSource,
            boolean mock,
            int attempt,
            int inputItems,
            long durationMs,
            TokenUsage tokenUsage
    ) {
        tokenUsage = effectiveUsage(tokenUsage);
        LOGGER.info(
                "event=model.call.completed requestId={} workflowId={} operation={} providerRoute={} "
                        + "model={} selectionSource={} mock={} attempt={} inputItems={} "
                        + "inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                LogFields.value(MDC.get("requestId")),
                LogFields.value(MDC.get("workflowId")),
                LogFields.value(operation),
                LogFields.value(providerRoute),
                LogFields.value(model),
                LogFields.value(selectionSource),
                mock,
                Math.max(1, attempt),
                Math.max(0, inputItems),
                tokenCount(tokenUsage == null ? null : tokenUsage.inputTokens()),
                tokenCount(tokenUsage == null ? null : tokenUsage.outputTokens()),
                tokenCount(tokenUsage == null ? null : tokenUsage.totalTokens()),
                Math.max(0, durationMs)
        );
        publish(operation, providerRoute, model, mock, attempt, true, null, null, false, durationMs, tokenUsage);
    }

    /** 记录失败类别和上游状态码；willRetry 仅控制日志级别，不改变 Provider 重试策略。 */
    public static void failed(
            String operation,
            String providerRoute,
            String model,
            String selectionSource,
            String failureType,
            boolean retryable,
            Integer upstreamStatus,
            boolean willRetry,
            int attempt,
            int inputItems,
            long durationMs
    ) {
        failed(operation, providerRoute, model, selectionSource, failureType, retryable,
                upstreamStatus, willRetry, attempt, inputItems, durationMs, null);
    }

    /**
     * 记录失败调用中上游已经明确返回的用量，例如结构解析成功但业务校验拒绝的响应。
     * 未收到响应或用量缺失时仍使用未知标记，不以零值或估算替代真实计费证据。
     */
    public static void failed(
            String operation,
            String providerRoute,
            String model,
            String selectionSource,
            String failureType,
            boolean retryable,
            Integer upstreamStatus,
            boolean willRetry,
            int attempt,
            int inputItems,
            long durationMs,
            TokenUsage tokenUsage
    ) {
        tokenUsage = effectiveUsage(tokenUsage);
        String message = "event=model.call.failed requestId={} workflowId={} operation={} providerRoute={} "
                + "model={} selectionSource={} failureType={} retryable={} upstreamStatus={} willRetry={} "
                + "attempt={} inputItems={} inputTokens={} outputTokens={} totalTokens={} durationMs={}";
        Object[] fields = {
                LogFields.value(MDC.get("requestId")),
                LogFields.value(MDC.get("workflowId")),
                LogFields.value(operation),
                LogFields.value(providerRoute),
                LogFields.value(model),
                LogFields.value(selectionSource),
                LogFields.value(failureType),
                retryable,
                upstreamStatus == null ? "-" : upstreamStatus,
                willRetry,
                Math.max(1, attempt),
                Math.max(0, inputItems),
                tokenCount(tokenUsage == null ? null : tokenUsage.inputTokens()),
                tokenCount(tokenUsage == null ? null : tokenUsage.outputTokens()),
                tokenCount(tokenUsage == null ? null : tokenUsage.totalTokens()),
                Math.max(0, durationMs)
        };
        if (willRetry) {
            LOGGER.warn(message, fields);
        } else {
            LOGGER.error(message, fields);
        }
        publish(operation, providerRoute, model, false, attempt, false, failureType, upstreamStatus,
                willRetry, durationMs, tokenUsage);
    }

    /** 开启单次尝试；修复重试使用新句柄，journal 重放复用同一事件 UUID。 */
    public static Attempt beginAttempt() {
        Attempt scope = new Attempt(CURRENT_ATTEMPT.get());
        CURRENT_ATTEMPT.set(scope);
        return scope;
    }

    /** 在解析正文或业务校验之前保留响应中的显式用量，失败路径也能对账。 */
    public static void captureTokenUsage(TokenUsage usage) {
        Attempt attempt = CURRENT_ATTEMPT.get();
        if (attempt != null) {
            attempt.usage = usage;
            attempt.transportState = "RESPONSE_RECEIVED";
        }
    }

    /** 传输层在获取并发名额前标记未发送，区分本地限流与上游实际调用。 */
    public static void transportPending() { markTransport("NOT_SENT", null); }

    /** 只在并发名额取得之后、真正执行 HTTP 传输之前调用。 */
    public static void transportStarted() { markTransport("STARTED", null); }

    /** 保留上游状态码，不读取或记录响应正文。 */
    public static void responseReceived(int status) { markTransport("RESPONSE_RECEIVED", status); }

    /** 修改当前尝试的传输证据；没有采集范围时对业务行为没有影响。 */
    private static void markTransport(String state, Integer status) {
        Attempt attempt = CURRENT_ATTEMPT.get();
        if (attempt != null) {
            attempt.transportState = state;
            if (status != null) attempt.upstreamStatus = status;
        }
    }

    /** 明确传入的用量优先；早期结构解析失败时读取尚未被丢弃的响应用量。 */
    private static TokenUsage effectiveUsage(TokenUsage usage) {
        Attempt attempt = CURRENT_ATTEMPT.get();
        return usage != null || attempt == null ? usage : attempt.usage;
    }

    /** 日志格式保持兼容；独立对账异常只输出安全类别，不替换模型调用的业务结果。 */
    private static void publish(String operation, String route, String model, boolean mock, int number,
                                boolean successful, String failure, Integer status, boolean willRetry,
                                long durationMs, TokenUsage usage) {
        ModelCallAuditContext.Snapshot context = ModelCallAuditContext.capture();
        if (context == null) return;
        Attempt attempt = CURRENT_ATTEMPT.get();
        if (attempt != null && attempt.published) return;
        if (attempt != null) attempt.published = true;
        try {
            context.sink().record(context.tenantId(), new ModelCallObservation(
                    attempt == null ? UUID.randomUUID() : attempt.eventId,
                    attempt == null ? OffsetDateTime.now(ZoneOffset.UTC) : attempt.startedAt,
                    MDC.get("requestId"), MDC.get("workflowId"), operation, route, model, number, mock,
                    successful, failure, status != null ? status : (attempt == null ? null : attempt.upstreamStatus),
                    willRetry, successful || status != null ? "RESPONSE_RECEIVED"
                            : (attempt == null ? "UNKNOWN" : attempt.transportState), durationMs, usage));
        } catch (RuntimeException exception) {
            LOGGER.error("event=analytics.model_call.capture_failed requestId={} failureType={}",
                    LogFields.value(MDC.get("requestId")), exception.getClass().getSimpleName());
        }
    }

    private static String tokenCount(Long value) {
        return value == null || value < 0 ? "-" : value.toString();
    }

    /** 仅承载上游明确返回的 token 用量；缺失用量保持 null，不估算。 */
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens,
                             Long cacheHitTokens, Long cacheMissTokens) {
        /** 兼容已有三字段调用；供应商未返回缓存用量时保持未知。 */
        public TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
            this(inputTokens, outputTokens, totalTokens, null, null);
        }

        /** 用量完整性独立于调用成功；负数或互相矛盾的用量不参与消费合计。 */
        public String usageState() {
            for (Long value : new Long[]{inputTokens, outputTokens, totalTokens, cacheHitTokens, cacheMissTokens}) {
                if (value != null && value < 0) return "INVALID";
            }
            if (inputTokens != null && outputTokens != null && totalTokens != null
                    && !sumEquals(inputTokens, outputTokens, totalTokens)) return "INVALID";
            if (inputTokens != null && ((cacheHitTokens != null && cacheHitTokens > inputTokens)
                    || (cacheMissTokens != null && cacheMissTokens > inputTokens))) return "INVALID";
            if (inputTokens != null && cacheHitTokens != null && cacheMissTokens != null
                    && !sumEquals(cacheHitTokens, cacheMissTokens, inputTokens)) return "INVALID";
            if (inputTokens == null && outputTokens == null && totalTokens == null) return "UNKNOWN";
            return inputTokens != null && outputTokens != null && totalTokens != null ? "COMPLETE" : "PARTIAL";
        }

        /** 按已报告的缓存 token 判定；缺少必要证据时保持 UNKNOWN。 */
        public String cacheState() {
            if ("INVALID".equals(usageState()) || (cacheHitTokens == null && cacheMissTokens == null)) return "UNKNOWN";
            if (cacheHitTokens != null && cacheHitTokens > 0) {
                return cacheMissTokens == null ? "UNKNOWN" : (cacheMissTokens > 0 ? "MIXED" : "HIT");
            }
            if (cacheHitTokens != null && cacheHitTokens == 0 && cacheMissTokens != null) {
                return cacheMissTokens > 0 ? "MISS" : "NONE";
            }
            return "UNKNOWN";
        }

        /** 用任意精度整数比较，避免恶意或损坏用量在求和时溢出。 */
        private static boolean sumEquals(long first, long second, long total) {
            return java.math.BigInteger.valueOf(first).add(java.math.BigInteger.valueOf(second))
                    .equals(java.math.BigInteger.valueOf(total));
        }
    }

    /** 保存一次尝试的安全计量证据；终态仅发布一次，关闭时恢复嵌套上下文。 */
    public static final class Attempt implements AutoCloseable {
        private final Attempt previous;
        private final UUID eventId = UUID.randomUUID();
        private final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        private TokenUsage usage;
        private Integer upstreamStatus;
        private String transportState = "UNKNOWN";
        private boolean published;
        private boolean closed;

        private Attempt(Attempt previous) { this.previous = previous; }

        /** 清除当前尝试，不将上一次失败的用量带入下次重试。 */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT_ATTEMPT.remove(); else CURRENT_ATTEMPT.set(previous);
        }
    }

}
