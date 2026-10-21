package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 从实际组装入口固定受众被移作研究对象与充分原文被重写的反例。 */
class TaskMeaningRegressionTest {
    private static final String RESEARCH = "为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。当前只是拟定研究，尚未实施。";

    @Test void rejectsObservedAudienceAsStudySubjectBeforeAnyTailRepair() {
        assertThatThrownBy(() -> assemble(RESEARCH,
                "本研究尚未实施。研究对象为研究生使用提示词完成任务的表现。", RESEARCH, null))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void preservesAudienceAndActualResearchTopic() {
        assertThatCode(() -> assemble(RESEARCH, "面向研究生读者，研究主题为Plan问答对提示词质量的影响。", RESEARCH, null))
                .doesNotThrowAnyException();
    }

    @Test void independentAudienceClauseDoesNotBecomePartOfTheStudySubject() {
        for (String background : List.of(
                "研究对象为Plan问答对提示词质量的影响，面向研究生读者。",
                "研究对象为Plan问答对提示词质量的影响，本文面向研究生。")) {
            assertThatCode(() -> assemble(RESEARCH, background, RESEARCH, null)).doesNotThrowAnyException();
        }
    }

    @Test void commaSeparatedStudyPopulationsStillCannotIncludeTheWritingAudience() {
        for (String background : List.of("研究对象为工程师，研究生。",
                "研究对象为工程师，研究生读者。", "研究对象为研究生，本文面向研究生。")) {
            assertThatThrownBy(() -> assemble(RESEARCH, background, RESEARCH, null))
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test void explicitSameAudienceAndStudySubjectIsLegitimate() {
        String raw = "为研究生撰写研究方案，研究对象为研究生，研究主题为学习压力。";
        assertThatCode(() -> assemble(raw, "研究对象为研究生。", raw, null)).doesNotThrowAnyException();
    }

    @Test void harmlessPolitenessNormalizationAndSectionAssignmentMustNotBlockExecution() {
        String raw = "请给同事小林写一封中文邮件，提醒他今天17点前把本周项目进度发给我，好汇总给团队。"
                + "语气友好，正文不超过80个汉字。只输出主题和正文，不写占位符，不补造项目进展，不举例。";
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,
                List.of(), TaskEnhancementAssessment.Presentation.COMPACT);
        var result = assemble(raw, "收件人是同事小林。",
                "给同事小林写一封中文邮件，提醒他今天17点前把本周项目进度发给我，好汇总给团队。语气友好。",
                "只输出主题和正文，正文不超过80个汉字。", "不写占位符，不补造项目进展，不举例。", assessment);
        assertThat(result.optimizedPrompt()).contains("小林", "今天17点", "正文不超过80个汉字", "只输出主题和正文", "不补造项目进展");
    }

    @Test void aRealSubjectChoiceCanNameTheAudienceButAuthorizationCannot() {
        var selected = new ConfirmedPlanDecision("population", "研究对象选择哪些人群？", "研究对象",
                ConfirmedPlanDecision.Scope.CHOICE, "本次选择研究生。");
        assertThatCode(() -> TaskMeaningContract.from(RESEARCH, List.of(selected))
                .validate("研究对象为研究生。", "background")).doesNotThrowAnyException();
        var permission = new ConfirmedPlanDecision("permission", "是否已获准使用研究对象的个人资料？", "授权",
                ConfirmedPlanDecision.Scope.CHOICE, "已获准。");
        assertThatThrownBy(() -> TaskMeaningContract.from(RESEARCH, List.of(permission))
                .validate("研究对象为研究生。", "background"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void anUnresolvedSubjectAnswerDoesNotConfirmThePopulation() {
        var undecided = new ConfirmedPlanDecision("population", "研究对象选择哪些人群？", "研究对象",
                ConfirmedPlanDecision.Scope.UNRESOLVED, "不知道，请提出有条件的建议。");
        assertThatThrownBy(() -> TaskMeaningContract.from(RESEARCH, List.of(undecided))
                .validate("研究对象为研究生。", "background"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void choosingAnotherPopulationDoesNotAlsoConfirmTheWritingAudience() {
        var selected = new ConfirmedPlanDecision("population", "研究对象选择哪些人群？", "研究对象",
                ConfirmedPlanDecision.Scope.CHOICE, "本次选择工程师。");
        assertThatThrownBy(() -> TaskMeaningContract.from(RESEARCH, List.of(selected))
                .validate("研究对象为研究生。", "background"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void anExplicitlyExcludedPopulationCannotBeConfirmedByMention() {
        var selected = new ConfirmedPlanDecision("population", "研究对象选择哪些人群？", "研究对象",
                ConfirmedPlanDecision.Scope.CHOICE, "不是研究生，本次选择工程师。");
        assertThatThrownBy(() -> TaskMeaningContract.from(RESEARCH, List.of(selected))
                .validate("研究对象为研究生。", "background"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void equalInformationInputIsNotARequestToRepeatTheQuestionnaire() {
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,
                List.of(), TaskEnhancementAssessment.Presentation.COMPACT);
        String request = RESEARCH + "\n\n本次测试实际确认信息（逐字题答）：\n问题：评分方式？\n回答：请提出推荐。";
        assertThatCode(() -> assemble(request, "面向研究生，研究Plan问答对提示词质量的影响。", RESEARCH, assessment))
                .doesNotThrowAnyException();
    }

    private OptimizationResult assemble(String raw, String background, String task, TaskEnhancementAssessment assessment) {
        return assemble(raw, background, task, "交付原文要求的内容。", "不编造事实。", assessment);
    }

    private OptimizationResult assemble(String raw, String background, String task, String output, String constraints,
            TaskEnhancementAssessment assessment) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND,"背景",background),
                new PromptSection(PromptSectionType.TASK,"任务",task),
                new PromptSection(PromptSectionType.OUTPUT,"输出",output),
                new PromptSection(PromptSectionType.CONSTRAINTS,"约束",constraints)),
                "test","test",false,List.of(),List.of(),assessment);
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"test"),
                new PromptTemplate(TemplateCode.GENERAL,"按原文交付。","符合原文。"),
                List.of(),List.of(),false,List.of(),1,raw);
    }
}
