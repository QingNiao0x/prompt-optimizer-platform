package com.promptoptimizer.provider.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 单次生成候选只替换第四版自检段，保留其余生成指导、来源协议和Plan行为。
 * 此处保护版本边界，不凭指导文本断言模型语义质量已经提升。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptSourceReviewInstructionsTest {
    /** 冻结的第四版替换边界；候选措辞不在测试中重复维护。 */
    private static final String FOURTH_REVIEW = """
            返回前对照原始提示词、全部实际回答和适用上下文，检查目标与对象是否偏移、原要求是否遗漏或改变，
            条件是否矛盾、交付是否变成空模板，以及同一要求是否无意义重复；发现问题先修正正文，再填写最终覆盖引用。
            """;

    @Test
    void candidateVersionKeepsTheDefaultAndAllExistingProtocolGates() {
        assertThat(NaturalPromptInstructions.VERSION).isEqualTo("4");
        assertThat(NaturalPromptSourceReviewInstructions.VERSION).isEqualTo("20");
        assertThat(NaturalPromptInstructions.supports("20")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding("20")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview("20")).isTrue();
        assertThat(NaturalPromptInstructions.enhancementInstruction("4")).isSameAs(NaturalPromptInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.enhancementInstruction("20")).isSameAs(NaturalPromptSourceReviewInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.planningInstruction("20"))
                .isSameAs(NaturalPromptInstructions.planningInstruction("4"));
        assertThat(NaturalPromptFidelityInstructions.supports("20")).isFalse();
    }

    @Test
    void candidateChangesOnlyTheUniqueFrozenSelfReviewBlock() {
        String fourth = NaturalPromptInstructions.enhancementInstruction("4");
        String candidate = NaturalPromptInstructions.enhancementInstruction("20");
        int boundary = fourth.indexOf(FOURTH_REVIEW);
        assertThat(boundary).isPositive();
        assertThat(fourth.lastIndexOf(FOURTH_REVIEW)).isEqualTo(boundary);
        String prefix = fourth.substring(0, boundary);
        String suffix = fourth.substring(boundary + FOURTH_REVIEW.length());
        assertThat(candidate).startsWith(prefix).endsWith(suffix).doesNotContain(FOURTH_REVIEW);
        String replacement = candidate.substring(prefix.length(), candidate.length() - suffix.length());
        assertThat(replacement).isNotBlank().isNotEqualTo(FOURTH_REVIEW);
        assertThat(candidate).startsWith(NaturalPromptInstructions.COMMON).endsWith(NaturalPromptInstructions.TASK_RECORD);
        assertThat(fourth).contains(FOURTH_REVIEW);
    }
}
