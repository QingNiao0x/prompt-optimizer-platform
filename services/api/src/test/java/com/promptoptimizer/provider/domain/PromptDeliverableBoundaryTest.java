package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
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
    @ValueSource(strings = {"提供可直接使用的论文方法部分写作提示词。", "准备一份写作提示词，用于撰写论文方法提纲。",
            "编写一份关于两个平台有什么优势的提示词模板。", "Create a prompt to compare one approach versus the other."})
    void allowsTheOriginalPromptDeliverable(String modelText) {
        assertThatCode(() -> DraftDeliveryContract.from("为研究生准备论文方法部分的写作提示词。", template, List.of())
                .validateDelivery(modelText, "sections.TASK")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"撰写研究报告。\n> 提供论文写作提示词。", "撰写研究报告。\n```\n提供论文写作提示词。\n```",
            "撰写研究报告。\n参考资料：提供论文写作提示词。", "撰写研究报告，不需要提供写作提示词。",
            "撰写研究报告，如果以后需要提供写作提示词，再另行处理。",
            "撰写研究报告。如果后续另行批准，再提供一份提示词。"})
    void referenceNegationAndFutureInstructionsDoNotChangeTheDeliverable(String raw) {
        assertThatCode(() -> DraftDeliveryContract.from(raw, template, List.of())
                .validateDelivery("撰写研究报告正文。", "sections.TASK")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请撰写宣传文案并制作 PPT，回答：您的产品相比直接让 Claude 优化提示词，核心优势是什么？",
            "后续要讲解产品。例如：现在模型优化能力已经很强, 相比直接优化提示词有什么优势？",
            "直接让 Claude 优化提示词，与这个平台有何区别？",
            "请写一篇介绍提示词优化平台优势的宣传文案。",
            "请为产品制作PPT，讲解它相较于直接让Claude优化提示词有什么优势。",
            "What are the benefits of letting Claude improve a prompt versus using this platform?"})
    void aComparisonQuestionDoesNotTurnMarketingCopyIntoPromptDelivery(String raw) {
        var contract = DraftDeliveryContract.from(raw, template, List.of());
        assertThat(contract.promptTarget()).isEmpty();
        assertThatCode(() -> contract.validateDelivery(
                "请比较平台与直接优化提示词的方式，说明优势和适用场景。",
                "sections.TASK")).doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validateDelivery("生成一份可直接使用的提示词。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class)
                .extracting(error -> ((ProviderResponseValidationException) error).getReason())
                .isEqualTo(ProviderResponseValidationException.Reason.DELIVERY_LEVEL_CONFLICT);
    }

    @Test
    void validatesJointCoverageAndRejectsAnExtraArtifactEvenWhenTheOriginalWorkIsRetained() {
        var original = DraftDeliveryContract.from("请先比较两个工具，再给我一份可复用的提示词。", template, List.of());
        assertThatCode(() -> original.validateSections(
                "比较两款工具。提供一份适用于这项比较的提示词。", "中文，三百字以内。")).doesNotThrowAnyException();
        assertThatThrownBy(() -> original.validateSections("比较两款工具。", "中文，三百字以内。"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error ->
                        assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.PROMPT_DELIVERY_MISSING));
        var copy = DraftDeliveryContract.from("写一份产品宣传文案。", template, List.of());
        assertThatThrownBy(() -> copy.validateSections("写一份宣传文案。", "另交付一份可直接使用的结构化提示词。"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.DELIVERY_LEVEL_CONFLICT);
                    assertThat(error.getField()).isEqualTo("sections.OUTPUT");
                });
    }

    @Test
    void boundChoiceCanNarrowDeliverablesWithoutUsingTheQuestionAsACommand() {
        String raw = "为这次产品宣讲准备一份提示词，也可以改用讲解稿。";
        var answer = new ConfirmedPlanDecision("q1", "本轮交付物是提示词还是讲解稿？", "交付物",
                ConfirmedPlanDecision.Scope.CHOICE, "只要讲解稿");
        var delivery = DraftDeliveryContract.from(raw, template, List.of(answer));
        assertThat(delivery.promptTarget()).isEmpty();
        assertThatCode(() -> delivery.validateSections("撰写一份产品讲解稿。", "只交付讲解稿。"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> delivery.validateSections("撰写一份产品讲解稿。", "并提供一份提示词。"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void roleSetupDoesNotReplaceThePromptChosenInPlan() {
        var answer = new ConfirmedPlanDecision("q1", "本轮要交付什么？", "交付物",
                ConfirmedPlanDecision.Scope.CHOICE, "一份提示词");
        var delivery = DraftDeliveryContract.from("你是一名会议助手。", template, List.of(answer));
        assertThat(delivery.promptTarget()).isNotBlank();
        assertThat(delivery.roleExecutionGuidance("你是一名会议助手。", template)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Translate ‘Write a prompt for a report.’ into Chinese. Return only the translation.",
            "翻译：准备论文写作提示词。只输出译文。"})
    void aTranslatedInstructionDoesNotBecomeTheCurrentDeliverable(String raw) {
        assertThatCode(() -> DraftDeliveryContract.from(raw, template, List.of())
                .validateDelivery("仅输出译文。", "sections.OUTPUT")).doesNotThrowAnyException();
    }
}
