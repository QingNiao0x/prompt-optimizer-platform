package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.NaturalPromptProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 离线自然链路示例，明确标记Mock；只验证协议与产品连通性，不冒充真实模型增强质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockNaturalPromptProvider implements NaturalPromptProvider {
    private static final ProviderMetadata MODEL = new ProviderMetadata("mock", "natural-offline-v1", true);

    @Override
    public <T> T enhance(NaturalPromptInput input, Function<NaturalPromptDraft, T> validation) {
        String body = input.rawPrompt().strip();
        if (!input.context().customDescription().isBlank()) body += "\n\n结合以下已提供背景完成任务：" + input.context().customDescription();
        if (!input.actualAnswers().isEmpty()) body += "\n\n遵守本次实际确认："
                + input.actualAnswers().stream().map(a -> a.question() + "：" + a.answer()).collect(Collectors.joining("；"));
        if (input.includeExamples()) body += "\n\n在原定格式与事实边界允许时，交付与任务相关的输入及预期输出示例。";
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "原定交付", List.of(), List.of(), List.of());
        T result = validation.apply(new NaturalPromptDraft(body, NaturalPromptDraft.Framework.APE, MODEL, task));
        ModelCallLogger.completed("prompt.optimize", "mock", MODEL.model(), "MOCK_PROVIDER", true, 1, 1, 0, null);
        return result;
    }

    @Override
    public <T> T plan(NaturalPromptInput input, Function<PlanningProviderResponse, T> validation) {
        // 离线示例不凭关键词编造未知；真实提问由独立模型策略负责。
        T result = validation.apply(new PlanningProviderResponse("将按原定目标和交付要求生成自然提示词。", List.of(),
                MODEL.provider(), MODEL.model(), true));
        ModelCallLogger.completed("plan.generate", "mock", MODEL.model(), "MOCK_PROVIDER", true, 1, 1, 0, null);
        return result;
    }
}
