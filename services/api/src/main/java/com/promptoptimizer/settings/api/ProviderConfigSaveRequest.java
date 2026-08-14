package com.promptoptimizer.settings.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新增 Provider 配置的请求。API Key 只允许写入，不允许读取。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ProviderConfigSaveRequest(
        @NotBlank(message = "供应商类型不能为空")
        @Pattern(regexp = "^(OPENAI|ANTHROPIC|DEEPSEEK|CUSTOM)$", message = "供应商类型不受支持")
        String providerType,
        @NotBlank(message = "配置显示名称不能为空")
        @Size(max = 80, message = "配置显示名称不能超过 80 个字符")
        String displayName,
        @NotBlank(message = "模型端点不能为空")
        @Size(max = 500, message = "模型端点不能超过 500 个字符")
        @Pattern(regexp = "^https?://.+$", message = "模型端点必须是 http 或 https 地址")
        String endpointUrl,
        @NotBlank(message = "模型名称不能为空")
        @Size(max = 120, message = "模型名称不能超过 120 个字符")
        String modelName,
        @NotBlank(message = "API Key 不能为空")
        @Size(max = 2_000, message = "API Key 长度异常")
        String apiKey,
        Map<String, Object> parameters,
        Boolean enabled
) {

    /**
     * 参数映射为空时转为空 Map，启用状态默认开启。
     */
    public ProviderConfigSaveRequest {
        parameters = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        enabled = enabled == null || enabled;
    }
}
