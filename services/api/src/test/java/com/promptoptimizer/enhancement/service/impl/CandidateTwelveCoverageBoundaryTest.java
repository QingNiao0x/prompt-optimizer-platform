package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对跨责任段覆盖、题答来源和委托范围，保护独立对象、条件、否定与真正用户选择。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwelveCoverageBoundaryTest {
    private static final String INTRO = "为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。";
    private static final String DELEGATION = "请根据原始研究目标提出可开展的推荐设计并说明适用条件；";

    @Test
    void completeTaskIntroductionCanBeDistributedAcrossItsResponsibilitySections() {
        assertThat(PlanDecisionIntegrator.coveredRule(sections(
                "面向研究生读者。研究主题为Plan问答对提示词质量的影响。",
                "撰写该研究设计的方法部分。", "交付中文研究设计的方法部分正文。"), INTRO)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"面向本科生读者。研究主题为Plan问答对提示词质量的影响。",
            "如果以后确认，面向研究生读者。研究主题为Plan问答对提示词质量的影响。",
            "研究对象为研究生。研究主题为Plan问答对提示词质量的影响。",
            "面向研究生读者。研究主题为提示词质量对Plan问答的影响。",
            "面向研究生读者。另一研究：研究主题为Plan问答对提示词质量的影响。",
            "面向研究生读者。示例：\n研究主题为Plan问答对提示词质量的影响。"})
    void partialDifferentFutureOrExampleAnchorsCannotCoverTheTask(String background) {
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background,
                "撰写该研究设计的方法部分。", "交付中文研究设计的方法部分正文。"), INTRO)).isFalse();
    }

    @Test
    void languageAndDeliveryLevelMustStillBeCovered() {
        var background = "面向研究生读者。研究主题为Plan问答对提示词质量的影响。";
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background,
                "撰写该研究设计的方法部分。", "交付英文研究设计的方法部分正文。"), INTRO)).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background,
                "先交付写作提示词。", "交付中文研究设计的方法部分写作提示词。"), INTRO)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"不应撰写该研究设计的方法部分。", "若以后确认，撰写该研究设计的方法部分。",
            "引用：\n撰写该研究设计的方法部分。", "## 另一研究\n撰写该研究设计的方法部分。"})
    void negativeFutureOrForeignExecutionCannotCoverTheWritingAction(String task) {
        assertThat(PlanDecisionIntegrator.coveredRule(sections("面向研究生读者。研究主题为Plan问答对提示词质量的影响。",
                task, "只提供材料。"), INTRO)).isFalse();
    }

    @Test
    void currentDelegationAndUnconfirmedParametersCanCoexist() {
        var body = sections("用户未确认任何既有参数，已明确委托提出可开展的推荐设计并说明适用条件。",
                "提出拟议主方案及适用条件。", "交付方法正文。");
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(body, DELEGATION)).isEmpty();
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(body, "不确认任何既有参数。")).isEmpty();
        assertThat(PlanDecisionIntegrator.coveredRule(body, "甲指标采用2020年标准人口。")).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(body, "采用年龄标准化。")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"用户未确认甲指标标准人口。", "用户已确认任何既有参数。",
            "如果以后确认，用户未确认任何既有参数。", "另一研究：\n用户未确认任何既有参数。",
            "用户未确认任何既有参数，但已确认本期参数。", "用户未确认任何既有参数，但可默认补全。"})
    void aDifferentPartialFutureOrContradictoryStatusCannotCoverNonConfirmation(String background) {
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background, "保留目标。", "交付正文。"),
                "不确认任何既有参数。")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"用户尚未明确委托提出可开展的推荐设计并说明适用条件。",
            "如果以后确认，用户已明确委托提出可开展的推荐设计并说明适用条件。",
            "另一研究：\n用户已明确委托提出可开展的推荐设计并说明适用条件。",
            "## 另一研究\n用户已明确委托提出可开展的推荐设计并说明适用条件。",
            "**示例：**\n用户已明确委托提出可开展的推荐设计并说明适用条件。",
            "示例：\n用户已明确委托提出可开展的推荐设计并说明适用条件。",
            "用户仅就甲指标明确委托提出可开展的推荐设计并说明适用条件。",
            "用户不必明确委托提出可开展的推荐设计并说明适用条件。",
            "用户已明确委托提出可开展的推荐设计，但适用条件以后再说。"})
    void onlyCompleteCurrentTaskDelegationCoversTheSource(String background) {
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(sections(background, "保留原目标。", "交付正文。"),
                DELEGATION)).containsExactly(DELEGATION.replaceAll("[；;]$", ""));
    }

    @Test
    void exceptionsAndAdditionalConditionsAreNotRemovedByGenericDelegation() {
        var body = sections("用户已明确委托提出可开展的推荐设计并说明适用条件。", "提出拟议方案。", "交付正文。");
        assertThat(PlanDecisionIntegrator.coveredRule(body,
                "请根据原始研究目标提出可开展的推荐设计并说明适用条件，但不采用问卷法。")).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(body,
                "甲院2024年请根据原始研究目标提出可开展的推荐设计并说明适用条件。")).isFalse();
    }

    @Test
    void completeOriginalCountAndComparisonRequirementsAreCoveredWithoutChangingTheirObjects() {
        String count = "设计必须保留：4类任务，每类5个，共20个任务";
        String comparison = "比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。";
        var body = sections("保留研究目标。", "任务与重复：保留 4 类任务、每类 5 个、共 20 个任务。"
                + "比较直接增强与Plan两种模式，并另设两组获得相同确认信息的公平对照。", "交付方法正文。");
        assertThat(PlanDecisionIntegrator.coveredRule(body, count)).isTrue();
        assertThat(PlanDecisionIntegrator.coveredRule(body, comparison)).isTrue();
        for (String changed : List.of("不保留4类任务，每类5个，共20个任务。", "保留5类任务，每类4个，共20个任务。",
                "另一研究：保留4类任务，每类5个，共20个任务。")) {
            assertThat(PlanDecisionIntegrator.coveredRule(sections("保留目标。", changed, "交付正文。"), count)).isFalse();
        }
        assertThat(PlanDecisionIntegrator.coveredRule(sections("保留目标。",
                "不比较直接增强与Plan两种模式，并另设两组获得相同确认信息的公平对照。", "交付正文。"), comparison)).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(body,
                "比较直接增强和Plan，并另设三组获得相同确认信息的公平对照。")).isFalse();
    }

    @Test
    void onlyCompleteIndependentProhibitionsAreOmittedAndExceptionsStayWhole() {
        var body = sections("尚未实施。", "撰写方法正文。", "不提供代码。不得把拟定样本写成已纳入样本。"
                + "不得编造效应量、P值、参考文献或显著性结论。不得使用假设数据或虚构数据。");
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(body,
                "不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。"))
                .isEmpty();
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(body, "不提供代码，不得编造访谈记录。"))
                .containsExactly("不得编造访谈记录。");
        String exception = "不提供代码，不得编造访谈记录，但允许引用可核实的真实记录。";
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(body, exception)).containsExactly(exception);
    }

    @Test
    void aVerbatimAnswerCaptionIsMetadataOnlyWhenFollowedByItsActualPair() {
        var guard = new RequirementFidelityGuard();
        String caption = "本次测试实际确认信息（逐字题答）：";
        var rules = guard.explicitRules(caption + "\n问题：任务类别如何设计？\n回答：" + DELEGATION + "不得编造数据。", List.of());
        assertThat(rules).doesNotContain(caption).anyMatch(value -> value.contains("不得编造数据"));
        assertThat(guard.explicitRules(caption, List.of())).contains(caption);
        assertThat(guard.explicitRules("必须保留标题：" + caption + "\n问题：任务类别如何设计？\n回答：" + DELEGATION,
                List.of())).anyMatch(value -> value.contains("必须保留标题"));
    }

    @Test
    void aBoundDelegationDoesNotReaskTheSameDesignButKeepsIndependentChoices() {
        String raw = "为研究生撰写中文研究设计的方法部分。当前只是拟定研究，尚未实施。说明评分设计。";
        var design = question("design", "这4类任务具体指哪四类？", "");
        var newCondition = question("new", "这4类任务具体指哪四类？", "另外确认新增指标的评分尺度。");
        var permission = question("permission", "实际参与者是否授权使用其个人资料？", "");
        var parameter = question("parameter", "甲指标采用哪一年的标准人口？", "");
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO,
                List.of(new PlanAnswer(design.id(), design.question(), DELEGATION + "不确认任何既有参数。")), List.of());
        var input = new PlanningProviderRequest(raw, "", List.of(), null, null, List.of(), "", task);
        assertThat(new PlanQuestionFilter().filter(List.of(design, newCondition, permission, parameter), input))
                .containsExactly(newCondition, permission, parameter);
        assertThat(task.pendingDecisions()).hasSize(1);
        var context = new com.promptoptimizer.context.domain.ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "v1");
        // 生产组装的提醒入口也必须拿到同一服务端任务视图，不能仅在直接调用过滤器时生效。
        assertThat(new PlanQuestionFilter().unresolvedFindings(List.of(design.question(), parameter.question()), raw,
                context, List.of(), task)).containsExactly(parameter.question());
        var future = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO,
                List.of(new PlanAnswer(design.id(), design.question(), "如果以后确认，再委托设计。")), List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(design), new PlanningProviderRequest(raw, "", List.of(), null,
                null, List.of(), "", future))).containsExactly(design);
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String background, String task, String output) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", background));
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", task));
        result.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", output));
        result.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留原要求。"));
        return result;
    }

    private PlanQuestion question(String id, String text, String hint) {
        return new PlanQuestion(id, text, hint, PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
