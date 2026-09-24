package com.promptoptimizer.provider.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 返回服务端配置的模型目录。
 *
 * <p>模型目录只暴露可展示的 ID 和名称；真实 API Key 永远不会进入响应。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@Profile("!local-mock")
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "openai-compatible")
@RequestMapping("/api/v1/models")
public class AvailableModelController {

    private final OpenAiCompatibleProperties properties;

    public AvailableModelController(OpenAiCompatibleProperties properties) {
        this.properties = properties;
    }

    /** 返回当前服务端允许使用的模型目录，仅包含公开元数据。 */
    @GetMapping
    public ApiResponse<List<AvailableModel>> list(HttpServletRequest request) {
        List<AvailableModel> models = properties.getAvailableModelDescriptors().stream()
                .map(model -> new AvailableModel(
                        model.id(),
                        displayName(model.model()),
                        model.provider(),
                        model.defaultModel()
                ))
                .toList();
        return ApiResponse.success(
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                models
        );
    }

    /** 将稳定的上游模型 ID 映射为官方名称；路由供应商由服务端使用，不拼入客户展示文案。 */
    private String displayName(String model) {
        String friendlyName = switch (model) {
            case "deepseek-flash" -> "DeepSeek-V4.1-Flash";
            case "hy4-preview" -> "Hy4 preview";
            case "hy3" -> "Hy3";
            case "glm-5.3" -> "GLM-5.3";
            case "kimi-k2.8-preview" -> "Kimi K2.8 Preview";
            case "kimi-k2.7-code" -> "Kimi K2.7 Code";
            case "glm-5.3-flashx" -> "GLM-5.3-FlashX";
            case "deepseek-v4-pro-0813" -> "DeepSeek-V4-Pro";
            case "kimi-k3" -> "Kimi K3";
            case "minimax-m3" -> "MiniMax-M3";
            default -> model;
        };
        return friendlyName;
    }
}
