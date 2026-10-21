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
    @Test void existingGenericReminderIsFilteredBeforeAssessmentConsistencyCheck() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "缺少研究执行步骤", "提出可操作的研究方案", "用户委托专业设计")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        var result = assembleResearch(assessment, List.of("  尚未明确输入来源、参数格式或调用方式。  "));
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.optimizedPrompt()).contains("研究设计").doesNotContain("尚未明确输入来源");
    }

    @Test void filteringGenericReminderDoesNotHideARealQuestionOrRelaxConflictingAssessment() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "缺少研究设计", "提出候选方案", "用户委托设计")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        assertThatThrownBy(() -> assembleResearch(assessment,
                List.of("尚未给出可验证的验收标准。", "是否允许使用真实受试者资料？")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        error -> assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT));
    }

    @Test void sensitiveFindingIsRejectedBeforeAnyGenericReminderFiltering() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "缺少研究设计", "提出候选方案", "用户委托设计")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        assertThatThrownBy(() -> assembleResearch(assessment,
                List.of("尚未给出可验证的验收标准。", "apiKey=sk-123456789012345678901234567890")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        error -> assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
    }

    @Test void genericModelReminderDoesNotDeleteServerDiscoveredSourceConflict() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "缺少研究设计", "提出候选方案", "用户委托设计")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
        String conflict = "资料对“研究对象”的适用范围存在冲突，须确认采用哪份资料。";
        var result = assembleResearch(assessment, List.of("尚未给出可验证的验收标准。"), List.of(conflict));
        assertThat(result.ambiguities()).contains(conflict);
        assertThat(result.optimizedPrompt()).contains("研究对象", "冲突");
    }

    @Test void requestsTaskKeepsRunnableCodeDeliveryWhenUrlIsAnExecutionParameter() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.INPUT_OUTPUT,
                        "未指定具体网址", "将网址作为输入参数", "用户要求通用抓取代码")),
                TaskEnhancementAssessment.Presentation.COMPACT);
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "需要获取网页标题。"),
                new PromptSection(PromptSectionType.TASK, "任务", "编写使用requests库请求网页并提取标题的Python代码，网址作为输入参数。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付可运行代码和运行方式。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "处理请求失败与缺少标题的情况，不编造抓取结果。")),
                "fixture", "fixture", false, List.of("尚未明确输入来源、参数格式或调用方式。"), List.of(), assessment);
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY"),
                new PromptTemplate(TemplateCode.FEATURE_DEVELOPMENT, "交付代码。", "处理错误。"),
                List.of(), List.of(), false, List.of(), 1, "使用requests库爬取网页标题。");
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.optimizedPrompt()).contains("requests", "网址作为输入参数", "可运行代码")
                .doesNotContain("尚未明确输入来源");
    }

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
        return assembleResearch(assessment, findings, List.of());
    }

    /** 服务端资料冲突独立于模型 assessment，不因移除固定泛化提醒而丢失。 */
    private com.promptoptimizer.enhancement.domain.OptimizationResult assembleResearch(
            TaskEnhancementAssessment assessment, List<String> findings, List<String> candidates) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "拟研究提示词质量。"),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写可以开展的研究设计。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付研究设计。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造结果。")),
                "test", "test", false, findings, List.of(), assessment);
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付研究设计。", "比较公平。"),
                candidates, List.of(), false, List.of(), 1, "撰写一个提示词质量的研究设计，要能实际开展。");
    }

    @Test void equivalentLanguageOrHeadingsDoNotPreclassifyRewriteDepth() {
        assertThat(PromptRewriteStrategy.forPrompt("当前项目已明确。只输出邮件正文。不得加附件，不要例子。").mode())
                .isEqualTo(PromptRewriteStrategy.forPrompt("Write the email body only. No attachments or examples.").mode());
    }
    @Test void studyingAnEnhancementWorkflowDoesNotMeanDeliveringAnotherPrompt() {
        var contract = DraftDeliveryContract.from("我想研究让用户先回答几个关键问题再增强提示词，是否比直接增强更有用。帮我写研究设计，要能实际开展，也要比较公平。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS,"交付研究设计。","比较公平。"),List.of());
        assertThat(contract.promptTarget()).isEmpty();
        assertThatCode(() -> contract.validateDelivery("交付研究设计，说明比较条件与评价办法。","sections.OUTPUT"))
                .doesNotThrowAnyException();
    }
    @Test void writingAStudyAboutPromptQualityIsNotWritingAPrompt() {
        var contract = DraftDeliveryContract.from("帮我写一个提示词质量的研究设计，要能实际开展。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付研究设计。", "比较公平。"), List.of());
        assertThat(contract.promptTarget()).isEmpty();
        var actualPrompt = DraftDeliveryContract.from("帮我写一个研究设计的写作提示词。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付提示词。", "比较公平。"), List.of());
        assertThat(actualPrompt.promptTarget()).isNotBlank();
    }
    @Test void researchStepsMayDescribeEnhancementButCannotReplaceTheFinalDeliverable() {
        var contract = DraftDeliveryContract.from("帮我写研究设计，比较先问答再增强提示词与直接增强。",
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS,"交付研究设计。","比较公平。"),List.of());
        assertThatCode(() -> contract.validateDelivery("设计一项研究方案，比较两种提示词增强流程：\n1. 先让用户回答关键问题，再基于回答增强提示词；\n2. 不询问用户，直接增强提示词。","sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validateDelivery("交付一份提示词。","sections.OUTPUT"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }
}
