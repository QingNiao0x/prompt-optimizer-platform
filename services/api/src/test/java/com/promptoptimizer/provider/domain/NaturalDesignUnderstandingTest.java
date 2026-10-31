package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 设计建议不需要伪造来源；事实来源与全部实际题答仍按原协议检查，不把出处存在当作语义保证。 */
class NaturalDesignUnderstandingTest {
    private static final String RAW = "为当前项目设计导出功能，技术细节由你决定。";
    private static final String ANSWER = "仅导出已筛选的数据，不读取其他记录。";
    private static final String PROPOSAL = "建议根据字段内容处理分隔符和换行。";

    @Test
    void aProposedDesignUsesGapAndBenefitWithoutInventingUserEvidence() {
        var resolved = task().resolve(input());
        resolved.review().validateEnhancement(resolved, RAW + ANSWER + PROPOSAL);
        assertThat(resolved.additions().getFirst().text()).isEqualTo(PROPOSAL);
        assertThat(resolved.requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        assertThat(resolved.requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
    }

    @Test
    void theSameDesignCannotBePresentedAsAQuoteFromTheUser() {
        var original = task();
        var forged = new NaturalTaskUnderstanding.Requirement(PROPOSAL,
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", PROPOSAL)),
                List.of(NaturalTaskUnderstanding.Dimension.CONDITION));
        var bad = new NaturalTaskUnderstanding(RAW, "导出功能", List.of(forged), original.additions(), List.of(), original.review());
        assertThatThrownBy(() -> bad.resolve(input())).isInstanceOf(ProviderException.class);
    }

    @Test
    void aProposedDesignDoesNotReplaceTheRealAnswerOrItsBodyCoverage() {
        var original = task();
        var omitted = new NaturalTaskUnderstanding(RAW, "导出功能", List.of(original.requirements().getFirst()),
                original.additions(), List.of(), original.review());
        assertThatThrownBy(() -> omitted.resolve(input())).isInstanceOf(NaturalTaskReview.MissingSources.class);
        assertThatThrownBy(() -> original.review().validateEnhancement(original, RAW + PROPOSAL))
                .isInstanceOf(ProviderException.class);
    }

    /** 使用实际协议的来源目录与答案来源编号，不由设计文本制造“用户确认”。 */
    private NaturalPromptInput input() {
        return new NaturalPromptInput(RAW, (ContextSnapshot) null,
                List.of(new PlanAnswer("scope", "导出范围？", ANSWER)), List.of(), List.of(), null)
                .withStrategyVersion(NaturalPromptCoreRepairInstructions.DESIGN_COMPLETION_VERSION);
    }

    /** 同一要求、建议和正文锚点进入生产核验，避免只检查枚举或提示词字面。 */
    private NaturalTaskUnderstanding task() {
        var requirements = List.of(
                new NaturalTaskUnderstanding.Requirement(RAW, List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", RAW)),
                        List.of(NaturalTaskUnderstanding.Dimension.GOAL)),
                new NaturalTaskUnderstanding.Requirement(ANSWER, List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", ANSWER)),
                        List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
        var review = new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION,
                List.of(new NaturalTaskReview.Coverage(0, List.of(RAW)), new NaturalTaskReview.Coverage(1, List.of(ANSWER))),
                List.of(new NaturalTaskReview.Coverage(0, List.of(PROPOSAL))), List.of());
        return new NaturalTaskUnderstanding(RAW, "导出功能", requirements,
                List.of(new NaturalTaskUnderstanding.Addition(PROPOSAL, "使不同字段内容可正确导出", "原任务未指定字段转义方式")),
                List.of(), review);
    }
}
