package com.promptoptimizer.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 统一记录模型调用的安全元数据，不接收或输出提示词、文件内容、凭据及上游响应正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelCallLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCallLogger.class);

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
        String message = "event=model.call.failed requestId=" + LogFields.value(MDC.get("requestId"))
                + " workflowId=" + LogFields.value(MDC.get("workflowId"))
                + " operation=" + LogFields.value(operation)
                + " providerRoute=" + LogFields.value(providerRoute)
                + " model=" + LogFields.value(model)
                + " selectionSource=" + LogFields.value(selectionSource)
                + " failureType=" + LogFields.value(failureType)
                + " retryable=" + retryable
                + " upstreamStatus=" + (upstreamStatus == null ? "-" : upstreamStatus)
                + " willRetry=" + willRetry
                + " attempt=" + Math.max(1, attempt)
                + " inputItems=" + Math.max(0, inputItems)
                + " inputTokens=- outputTokens=- totalTokens=-"
                + " durationMs=" + Math.max(0, durationMs);
        if (willRetry) {
            LOGGER.warn(message);
        } else {
            LOGGER.error(message);
        }
    }

    private static String tokenCount(Long value) {
        return value == null || value < 0 ? "-" : value.toString();
    }

    /** 仅承载上游明确返回的 token 用量；缺失用量保持 null，不估算。 */
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
    }

}
