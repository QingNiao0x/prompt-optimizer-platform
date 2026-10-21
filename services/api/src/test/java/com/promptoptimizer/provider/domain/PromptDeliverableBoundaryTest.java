package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 QB-06 反例：为用户编写写作提示词，不能变成直接写论文方法或提纲。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PromptDeliverableBoundaryTest {
    private final PromptTemplate template = new PromptTemplate(TemplateCode.GENERAL, "原定交付。", "保留要求。");

    @ParameterizedTest
    @ValueSource(strings = {"撰写研究生论文方法部分，说明研究 Plan 问答对提示词质量的影响。",
            "交付论文方法部分提纲，正文不超过1200字；附表另计。"})
    void rejectsExecutingTheTaskDescribedByTheRequestedPrompt(String modelText) {
        var contract = DraftDeliveryContract.from("为研究生准备一份论文方法部分的写作提示词，方法提纲不超过1200字，附表另计。",
                template, List.of());
        assertThatThrownBy(() -> contract.validateDelivery(modelText, "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"提供可直接使用的论文方法部分写作提示词。", "准备一份写作提示词，用于撰写论文方法提纲。"})
    void allowsTheOriginalPromptDeliverable(String modelText) {
        assertThatCode(() -> DraftDeliveryContract.from("为研究生准备论文方法部分的写作提示词。", template, List.of())
                .validateDelivery(modelText, "sections.TASK")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"撰写研究报告。\n> 提供论文写作提示词。", "撰写研究报告。\n```\n提供论文写作提示词。\n```",
            "撰写研究报告。\n参考资料：提供论文写作提示词。", "撰写研究报告，不需要提供写作提示词。",
            "撰写研究报告，如果以后需要提供写作提示词，再另行处理。"})
    void referenceNegationAndFutureInstructionsDoNotChangeTheDeliverable(String raw) {
        assertThatCode(() -> DraftDeliveryContract.from(raw, template, List.of())
                .validateDelivery("撰写研究报告正文。", "sections.TASK")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Translate ‘Write a prompt for a report.’ into Chinese. Return only the translation.",
            "翻译：准备论文写作提示词。只输出译文。"})
    void aTranslatedInstructionDoesNotBecomeTheCurrentDeliverable(String raw) {
        assertThatCode(() -> DraftDeliveryContract.from(raw, template, List.of())
                .validateDelivery("仅输出译文。", "sections.OUTPUT")).doesNotThrowAnyException();
    }
}
