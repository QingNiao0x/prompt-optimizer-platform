package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 240真实原件中的委托交付与评分缺项：正向执行和参数未确认并存，不扩展到其他对象或用户保留选择。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateFifteenDelegatedDeliveryTest {
    private static final String RAW = "撰写中文研究设计的方法部分。说明准确性、完整性、清晰度与可执行性评价以及分歧处理。";
    private static final String ANSWER = "这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。";
    private static final String QUESTION = "公平对照组的确认信息如何取得？";
    private static final String PREFIX = "已委托提出拟议设计，实际采用参数仍未确认：";
    private static final String HEADER = "对以下已委托设计的子项给出拟议主方案及适用条件，标明属于拟议设计，不冒充已确认或已实施：";
    private static final String SCALE = "针对原要求的准确性、完整性、清晰度与可执行性，提出拟议评分尺度、可操作定义和与尺度相配的分歧处理规则，并说明适用条件。";

    @Test
    void boundDelegationBecomesAnExecutionObligationAndKeepsAllSourcesAndPendingState() {
        var task = task(RAW, QUESTION, ANSWER);
        var sections = sections("撰写方法部分。");
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains(HEADER + "公平对照组的确认信息获取流程。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).isEmpty();
        assertThat(task.pendingDecisions()).hasSize(1);
        assertThat(task.modelInput().pendingDecisions().getFirst().designDelegated()).isTrue();
        assertThat(task.modelInput().executionRequirements()).allMatch(requirement -> requirement.sourceQuestionIds().contains("q-current"));
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).isEqualTo("不确认任何既有参数。");
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task);
        assertThat(sections.get(PromptSectionType.TASK).content().split(HEADER, -1)).hasSize(2);
    }

    @Test
    void crossParagraphCoverageAvoidsAnotherProjectionAndANewExplanationRemains() {
        var task = task(RAW, QUESTION, ANSWER);
        var sections = sections(HEADER + "\n盲评的设盲范围；\n公平对照组的确认信息获取与封存流程。");
        String old = sections.get(PromptSectionType.TASK).content();
        String extra = PREFIX + QUESTION + " 用户说明：仅允许甲院2025年版本。";
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(extra, PREFIX + QUESTION), task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(old);
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(extra, PREFIX + QUESTION), task)).containsExactly(extra);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后取得授权再提出拟议设计。", "确认评分方法，确认信息来源尚未确定。", "不推荐设计，只列出未决事项。", "尚未确定。"})
    void partialFutureOrDeniedDelegationNeverBecomesCurrentPermission(String answer) {
        var sections = sections("撰写方法部分。");
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task(RAW, QUESTION, answer));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("撰写方法部分。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲院2024年公平对照组的确认信息如何取得？", "乙院2025年公平对照组的确认信息如何取得？"})
    void newInstitutionAndYearRetainTheirOwnCompleteObject(String question) {
        var sections = sections(HEADER + "公平对照组的确认信息获取流程。");
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + question), task(RAW, question, ANSWER));
        assertThat(sections.get(PromptSectionType.TASK).content()).contains(question.replace("如何取得？", "获取流程。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果授权后公平对照组的确认信息如何取得？", "公平对照组的确认信息如何取得？但仅限2025年。", "不得获取的公平对照组信息如何取得？", "评分尺度是否采用1至5分？"})
    void unrecognizedQuestionsAndConditionsKeepTheOriginalReminder(String question) {
        var sections = sections("撰写方法部分。");
        var task = task(RAW, question, ANSWER);
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + question), task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("撰写方法部分。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + question), task)).containsExactly(PREFIX + question);
    }

    @Test
    void noCompleteParameterStateAndAdditionalConditionsPreventProjection() {
        var sections = sections("撰写方法部分。");
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "评分尺度尚未确认。"));
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task(RAW, QUESTION, ANSWER));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("撰写方法部分。");
    }

    @Test
    void declaredScoringCompletionReachesTheTaskWithoutDefaultingAnyValue() {
        var sections = sections("说明四维评价和分歧处理。");
        var task = task(RAW, QUESTION, ANSWER);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION));
        assertThat(sections.get(PromptSectionType.TASK).content()).contains(SCALE).doesNotContain("1至5", "1—5", "ICC", "阈值为");
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains("不确认任何既有参数");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION));
        assertThat(sections.get(PromptSectionType.TASK).content().split(SCALE, -1)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "撰写中文研究设计的方法部分。只说明准确性评价以及分歧处理。",
            "翻译下文为英文：准确性、完整性、清晰度与可执行性评价以及分歧处理。",
            "撰写中文研究设计的方法部分。\n> 说明准确性、完整性、清晰度与可执行性评价以及分歧处理。",
            "撰写中文研究设计的方法部分。\n如果以后确认，再说明准确性、完整性、清晰度与可执行性评价以及分歧处理。",
            RAW + "不得提出评分尺度。",
            RAW + "评分尺度确认后再推荐。",
            RAW + "评分尺度采用1至5分量表。"
    })
    void optionalMetadataCannotWidenTheTaskOrOverrideAReservedOrChosenScale(String raw) {
        var sections = sections("保持原要求。");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(raw, QUESTION, ANSWER), assessment(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保持原要求。");
    }

    @Test
    void metadataWithoutCurrentDelegationAndAUserDecisionDoNotAuthorizeCompletion() {
        var sections = sections("保持原要求。");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, QUESTION, "如果以后确认，再推荐。"), assessment(TaskEnhancementAssessment.Kind.EXECUTION_STEP));
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, QUESTION, ANSWER), assessment(TaskEnhancementAssessment.Kind.USER_DECISION));
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, QUESTION, ANSWER), null);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保持原要求。");
    }

    @Test
    void aDifferentStudiesScaleOrAFutureScaleDoesNotCoverTheCurrentObligation() {
        var sections = sections("其他研究：\n" + SCALE);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, QUESTION, ANSWER), assessment(TaskEnhancementAssessment.Kind.EXECUTION_STEP));
        assertThat(sections.get(PromptSectionType.TASK).content()).endsWith(SCALE);
        assertThat(sections.get(PromptSectionType.TASK).content().split(SCALE, -1)).hasSize(3);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("本任务：");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, QUESTION, ANSWER), assessment(TaskEnhancementAssessment.Kind.EXECUTION_STEP));
        assertThat(sections.get(PromptSectionType.TASK).content().split(SCALE, -1)).hasSize(3);
    }

    /** 所有权限都来自绑定题答；评分声明本身不产生确认或参数。 */
    private EffectiveTaskView task(String raw, String question, String answer) {
        return EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(new PlanAnswer("q-current", question, answer)), List.of(), false);
    }

    /** 模型声明仅作为缺项信号，数值例子不会抄进执行义务。 */
    private TaskEnhancementAssessment assessment(TaskEnhancementAssessment.Kind kind) {
        return new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(kind, "评分尺度尚未落实。", "提出拟议评分量表和操作定义，如1至5分。", "原要求含四维评价。")),
                TaskEnhancementAssessment.Presentation.STRUCTURED);
    }

    /** 用实际责任段检查追加与保留；引用段不构成当前执行来源。 */
    private EnumMap<PromptSectionType, PromptSection> sections(String task) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", "本研究尚未实施。"));
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", task));
        result.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "交付方法部分。"));
        result.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不确认任何既有参数。"));
        return result;
    }
}
