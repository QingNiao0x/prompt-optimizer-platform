package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证默认离线Provider与内部覆盖协议采用同一换行边界，原始任务及实际回答不因引用规范被误拒。
 * 不将确定性Mock正文当作实际增强质量证据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MockNaturalPromptProviderTest {
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029", "\u000b", "\f"})
    void acceptsSupportedLineBreaksWithoutChangingRawOrConfirmedText(String lineBreak) {
        String raw = "\u0085写一份交接说明。" + lineBreak + "只输出正文。";
        String answer = "\u0085接收对象为接班同事。" + lineBreak + "具体期限仍待确认。";
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var input = new NaturalPromptInput(raw, context, List.of(new PlanAnswer("q1", "对象与期限？", answer)),
                List.of(), false, List.of(), "mock:local");
        var provider = new MockNaturalPromptProvider();

        var result = provider.enhance(input, draft -> {
            var task = draft.taskUnderstanding().resolve(input);
            task.review().validateEnhancement(task, draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).contains(raw, answer);
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().review().requirementCoverage())
                .allSatisfy(coverage -> assertThat(coverage.quotes()).allMatch(quote -> !quote.matches("(?s).*\\R.*")));

        var planInput = new NaturalPromptInput(raw, context, List.of(), List.of(), false, List.of(), "mock:local");
        var plan = provider.plan(planInput, response -> {
            var task = response.taskUnderstanding().resolve(planInput);
            task.review().validatePlan(java.util.Set.of());
            return response;
        });
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.taskUnderstanding().review().requirementCoverage()).isEmpty();
    }
}
