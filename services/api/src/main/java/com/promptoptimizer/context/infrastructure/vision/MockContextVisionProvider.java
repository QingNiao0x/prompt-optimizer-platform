package com.promptoptimizer.context.infrastructure.vision;

import com.promptoptimizer.context.domain.ContextVisionModels.Recognition;
import com.promptoptimizer.context.service.ContextVisionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 显式 Mock 模式的协议演示，不将固定文本伪装成真实转录。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockContextVisionProvider implements ContextVisionProvider {
    @Override
    public Recognition recognize(String modelId, int page, byte[] png, int maxOutputTokens) {
        return new Recognition("[演示转录：未调用真实视觉模型]", List.of(), "本机演示资料，不能作为图片内容事实。",
                List.of("本机演示不提供真实文字识别"), false, true);
    }
}
