package com.promptoptimizer.settings.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 更新 Provider 配置的请求，所有字段均可选。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ProviderConfigUpdateRequest(
        @Size(max = 80, message = "配置显示名称不能超过 80 个字符")
        String displayName,
        @Size(max = 500, message = "模型端点不能超过 500 个字符")
        @Pattern(regexp = "^https?://.+$", message = "模型端点必须是 http 或 https 地址")
        String endpointUrl,
        @Size(max = 120, message = "模型名称不能超过 120 个字符")
        String modelName,
        @Size(max = 2_000, message = "API Key 长度异常")
        String apiKey,
        Map<String, Object> parameters,
        Boolean enabled
) {
}
