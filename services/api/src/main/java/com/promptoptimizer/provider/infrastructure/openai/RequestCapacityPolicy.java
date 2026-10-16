package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.provider.domain.ProviderCapacityException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 在所有消息组装后按路由预算核算，含修复消息；MVP使用保守UTF-8字节估算，非精确Tokenizer。
 * 未配置容量的路由仅受部署请求字节限额保护，不声明已验证其厂商上下文窗口。
 */
public final class RequestCapacityPolicy {
    /** 返回可发送的输出预留；不截断任何消息或切换模型。 */
    public int outputLimit(String modelId, List<String> messages, int requested, Map<String, Limit> limits) {
        long estimated = estimateInput(messages);
        if (estimated > 1_048_576L) throw new ProviderCapacityException();
        Limit limit = limits.get(modelId);
        if (limit == null) return requested;
        int output = Math.min(requested, limit.maxOutputTokens());
        if (estimated + output + limit.safetyMarginTokens() > limit.contextTokens()) throw new ProviderCapacityException();
        return output;
    }

    /** 对实际消息正文及每条协议开销作保守估算；估算单位不显示为精确Token计数。 */
    public long estimateInput(List<String> messages) {
        long bytes = 256;
        for (String message : messages) bytes += 64L + (message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length);
        return bytes;
    }

    /** 服务端容量快照，来源和核对日期用于维护；值不能从用户请求或模型声明中取得。 */
    public record Limit(long contextTokens, int maxOutputTokens, int safetyMarginTokens, String source, String verifiedAt) {
        public Limit {
            if (contextTokens <= 0 || maxOutputTokens <= 0 || safetyMarginTokens < 0
                    || source == null || source.isBlank() || verifiedAt == null || verifiedAt.isBlank()) {
                throw new IllegalArgumentException("Capacity policy requires positive limits and provenance");
            }
        }
    }
}
