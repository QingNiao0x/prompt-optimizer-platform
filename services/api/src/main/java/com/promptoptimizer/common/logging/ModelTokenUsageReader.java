package com.promptoptimizer.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从有界的 HTTP 错误响应提取 OpenAI 兼容数字用量，仅在进程内解析正文。
 * 返回值没有正文、错误消息、身份或凭据；解析失败不替换原始上游异常。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelTokenUsageReader {
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelTokenUsageReader.class);

    private ModelTokenUsageReader() { }

    /** HTTP 非 2xx 也可能包含用量；没有数字证据时返回未知，绝不记录错误正文。 */
    public static ModelCallLogger.TokenUsage readErrorUsage(byte[] response) {
        if (response == null || response.length == 0) return null;
        if (response.length > MAX_ERROR_BYTES) {
            LOGGER.warn("event=model.call.usage_unavailable reason=ERROR_BODY_TOO_LARGE");
            return null;
        }
        try {
            JsonNode usage = JSON.readTree(response).path("usage");
            if (!usage.isObject()) return null;
            return new ModelCallLogger.TokenUsage(number(usage, "prompt_tokens"), number(usage, "completion_tokens"),
                    number(usage, "total_tokens"), number(usage, "prompt_cache_hit_tokens"), number(usage, "prompt_cache_miss_tokens"));
        } catch (java.io.IOException | RuntimeException exception) {
            // 只报告稳定原因；Jackson 异常消息可能引用响应片段，禁止输出 exception 或 message。
            LOGGER.warn("event=model.call.usage_unavailable reason=INVALID_ERROR_USAGE");
            return null;
        }
    }

    /** 只接受明确的整数；不解析自由文本、不四舍五入、不估算 token。 */
    private static Long number(JsonNode usage, String name) {
        JsonNode value = usage.get(name);
        if (value == null || value.isNull()) return null;
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : -1L;
    }
}
