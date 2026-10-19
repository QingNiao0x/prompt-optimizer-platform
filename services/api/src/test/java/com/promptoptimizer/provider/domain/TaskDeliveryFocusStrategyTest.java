package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查一般交付不会收到其他研究题的对照实验指导；生成质量仍由冻结真实对照验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskDeliveryFocusStrategyTest {
    @Test
    void ordinaryCompatibilityEntryDoesNotInjectAnEvaluationStudy() {
        var instruction = PromptRewriteStrategy.forPrompt("给同事写一条50字以内的催办消息，只输出消息正文。").instruction();
        assertThat(instruction).doesNotContain("同信息对照两组", "随机化", "同一案例的实际答案");
    }

    @Test
    void researchLookupDoesNotBecomeAnAiPromptEvaluationExperiment() {
        String raw = "请帮我查询全球使用AI最多的职业/行业";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var strategy = PromptRewriteStrategy.forTask(task, PromptFormat.STRUCTURED);
        assertThat(strategy.instruction()).doesNotContain("同信息对照两组", "随机化", "同一案例的实际答案");
        assertThat(task.originalPrompt()).isEqualTo(raw);
        assertThat(task.effectivePrompt()).isEqualTo(raw);
    }
}
