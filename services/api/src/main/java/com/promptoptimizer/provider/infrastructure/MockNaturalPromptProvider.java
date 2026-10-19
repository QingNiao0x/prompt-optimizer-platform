package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.NaturalPromptProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
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
        var task = understanding(input, body);
        T result = validation.apply(new NaturalPromptDraft(body, NaturalPromptDraft.Framework.APE, MODEL, task));
        ModelCallLogger.completed("prompt.optimize", "mock", MODEL.model(), "MOCK_PROVIDER", true, 1, 1, 0, null);
        return result;
    }

    @Override
    public <T> T plan(NaturalPromptInput input, Function<PlanningProviderResponse, T> validation) {
        // 离线示例不凭关键词编造未知；真实提问由独立模型策略负责。
        T result = validation.apply(new PlanningProviderResponse("将按原定目标和交付要求生成自然提示词。", List.of(),
                MODEL.provider(), MODEL.model(), true,
                understanding(input, null)));
        ModelCallLogger.completed("plan.generate", "mock", MODEL.model(), "MOCK_PROVIDER", true, 1, 1, 0, null);
        return result;
    }

    /** 离线夹具只提供可验证的原句锚点，不凭确定性文本宣称已完成真实语义增强。 */
    private NaturalTaskUnderstanding understanding(NaturalPromptInput input, String body) {
        if (!NaturalPromptInstructions.requiresTaskReview(input.strategyVersion())) {
            return new NaturalTaskUnderstanding(input.rawPrompt(), "原定交付", List.of(), List.of(), List.of());
        }
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        requirements.add(new NaturalTaskUnderstanding.Requirement(input.rawPrompt(),
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", input.rawPrompt().strip())),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
        if (body != null) coverage.add(new NaturalTaskReview.Coverage(0, List.of(coverageQuote(input.rawPrompt()))));
        for (int i = 0; i < input.actualAnswers().size(); i++) {
            var answer = input.actualAnswers().get(i);
            requirements.add(new NaturalTaskUnderstanding.Requirement(answer.answer(),
                    List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers." + i, answer.answer())),
                    List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
            if (body != null) coverage.add(new NaturalTaskReview.Coverage(i + 1, List.of(coverageQuote(answer.answer()))));
        }
        return new NaturalTaskUnderstanding(input.rawPrompt(), "原定交付", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
    }

    /** 内部锚点采用与校验相同的换行边界；跳过空行，正文及真实来源不做替换或删减。 */
    private String coverageQuote(String text) {
        return java.util.Arrays.stream(text.split("\\R")).map(String::strip)
                .filter(line -> !line.isBlank()).findFirst().orElseThrow();
    }
}
