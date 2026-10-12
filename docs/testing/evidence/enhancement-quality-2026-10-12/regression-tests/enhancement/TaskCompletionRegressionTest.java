package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.DraftDeliveryContract;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 需求充分性不能由中文标题或限制词数量代替，研究对象也不能变成交付对象。 */
class TaskCompletionRegressionTest {
    @Test void professionalDesignAdviceCannotAlsoBecomeARequiredUserDecision() {
        // 冻结真实失败：模型只声明专业设计缺口，却把同一设计工作放入执行前确认清单。
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "缺少研究设计与评分办法", "提出可操作的研究方案", "用户委托设计研究")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        assertThatThrownBy(() -> assembleResearch(assessment, List.of("研究方法与评价指标尚未确定，需用户先确认。")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        error -> assertThat(error.getReason().name()).isEqualTo("ASSESSMENT_DECISION_CONFLICT"));
    }

    @Test void genuineUserDecisionsRemainPendingAndAreNotSilentlyDropped() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.USER_DECISION,
                        "是否允许使用真实受试者资料", "由用户确认授权", "资料权限不能由执行者推定")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        var result = assembleResearch(assessment, List.of("是否允许使用真实受试者资料？该授权须由用户确认。"));
        assertThat(result.optimizedPrompt()).contains("真实受试者资料", "用户确认");
    }

    /** 使用正式组装路径，区分模型自报建议和具有授权含义的真实未决事项。 */
    private com.promptoptimizer.enhancement.domain.OptimizationResult assembleResearch(
            TaskEnhancementAssessment assessment, List<String> findings) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "拟研究提示词质量。"),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写可以开展的研究设计。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付研究设计。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造结果。")),
                "test", "test", false, findings, List.of(), assessment);
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付研究设计。", "比较公平。", "示例。"),
                List.of(), List.of(), false, List.of(), false, 1, "撰写一个提示词质量的研究设计，要能实际开展。");
    }

    @Test void equivalentLanguageOrHeadingsDoNotPreclassifyRewriteDepth() {
        assertThat(PromptRewriteStrategy.forPrompt("当前项目已明确。只输出邮件正文。不得加附件，不要例子。").mode())
                .isEqualTo(PromptRewriteStrategy.forPrompt("Write the email body only. No attachments or examples.").mode());
    }
    @Test void studyingAnEnhancementWorkflowDoesNotMeanDeliveringAnotherPrompt() {
        var contract = DraftDeliveryContract.from("我想研究让用户先回答几个关键问题再增强提示词，是否比直接增强更有用。帮我写研究设计，要能实际开展，也要比较公平。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS,"交付研究设计。","比较公平。","示例。"),List.of());
        assertThat(contract.promptTarget()).isEmpty();
        assertThatCode(() -> contract.validateDelivery("交付研究设计，说明比较条件与评价办法。","sections.OUTPUT"))
                .doesNotThrowAnyException();
    }
    @Test void writingAStudyAboutPromptQualityIsNotWritingAPrompt() {
        var contract = DraftDeliveryContract.from("帮我写一个提示词质量的研究设计，要能实际开展。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付研究设计。", "比较公平。", "示例。"), List.of());
        assertThat(contract.promptTarget()).isEmpty();
        var actualPrompt = DraftDeliveryContract.from("帮我写一个研究设计的写作提示词。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付提示词。", "比较公平。", "示例。"), List.of());
        assertThat(actualPrompt.promptTarget()).isNotBlank();
    }
    @Test void researchStepsMayDescribeEnhancementButCannotReplaceTheFinalDeliverable() {
        var contract = DraftDeliveryContract.from("帮我写研究设计，比较先问答再增强提示词与直接增强。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS,"交付研究设计。","比较公平。","示例。"),List.of());
        assertThatCode(() -> contract.validateDelivery("设计一项研究方案，比较两种提示词增强流程：\n1. 先让用户回答关键问题，再基于回答增强提示词；\n2. 不询问用户，直接增强提示词。","sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validateDelivery("交付一份提示词。","sections.OUTPUT"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }
}
