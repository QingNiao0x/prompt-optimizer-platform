package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归230／231的当前状态与委托列表，保护独立对象、参数、未来、否定及所有来源身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateFourteenCoverageBoundaryTest {
    private static final String QUESTION = "公平对照组的确认信息如何取得？";
    private static final String ANSWER = "这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。";
    private static final String PREFIX = "已委托提出拟议设计，实际采用参数仍未确认：";
    private static final String HEADER = "对以下已委托设计的子项给出拟议主方案及适用条件，标明属于拟议设计，不冒充已确认或已实施：";

    @ParameterizedTest
    @ValueSource(strings = {
            "当前仅为拟定研究，尚未实施，不得使用假设数据。",
            "目前只是拟定研究，尚未实施，禁止编造假设样本。",
            "本研究尚未实施，不允许使用假设结果。"
    })
    void prohibitionIsNotAConditionForTheCurrentStudyState(String background) {
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background, "撰写研究设计。", ""),
                "当前只是拟定研究，尚未实施。")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果以后开展：当前仅为拟定研究，尚未实施，不得使用假设数据。",
            "假设当前仅为拟定研究，尚未实施。",
            "当前仅为拟定研究，尚未实施，但仅限甲院。",
            "另一研究：\n当前仅为拟定研究，尚未实施，不得使用假设数据。",
            "> 当前仅为拟定研究，尚未实施，不得使用假设数据。"
    })
    void actualConditionsAndForeignObjectsStillNeedTheCurrentRule(String background) {
        assertThat(PlanDecisionIntegrator.coveredRule(sections(background, "撰写研究设计。", ""),
                "当前只是拟定研究，尚未实施。")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "公平对照组的确认信息获取流程。",
            "公平对照组的确认信息取得流程。",
            "\n公平对照组的确认信息获取与封存流程。",
            "盲评的设盲范围；公平对照组的确认信息获取流程。"
    })
    void fullSameObjectDesignAndUnconfirmedStateAvoidOnlyTheCopiedReminder(String field) {
        var task = task(QUESTION, ANSWER);
        var sections = sections("研究尚未实施。", HEADER + field, "不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).isEmpty();
        assertThat(task.pendingDecisions()).hasSize(1);
        assertThat(task.modelInput().pendingDecisions().getFirst().designDelegated()).isTrue();
        assertThat(task.modelInput().executionRequirements()).allMatch(value -> value.sourceQuestionIds().contains("q-current"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "只提供建议：公平对照组的确认信息获取流程。",
            "如果以后确认，" + HEADER + "公平对照组的确认信息获取流程。",
            HEADER + "其他公平对照组的确认信息获取流程。",
            HEADER + "甲研究公平对照组的确认信息获取流程。",
            HEADER + "公平对照组的确认信息获取流程仅限2024年。",
            HEADER + "公平对照组的确认信息获取流程，除非之后获得授权。",
            HEADER + "公平对照组的确认信息获取流程；但仅限2024年。",
            HEADER + "公平对照组的确认信息获取流程；如果以后获得授权才开展。",
            HEADER + "不得给出公平对照组的确认信息获取流程。",
            "对以下已委托设计的子项不要给出拟议主方案及适用条件：公平对照组的确认信息获取流程。",
            "对以下已委托设计的子项不允许给出拟议主方案及适用条件：公平对照组的确认信息获取流程。",
            "示例：\n" + HEADER + "公平对照组的确认信息获取流程。",
            "> " + HEADER + "公平对照组的确认信息获取流程。",
            HEADER + "公平对照组的评分方法。"
    })
    void partialForeignConditionalOrDeniedDesignKeepsTheWholeReminder(String content) {
        var task = task(QUESTION, ANSWER);
        var sections = sections("研究尚未实施。", content, "不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task))
                .containsExactly(PREFIX + QUESTION);
    }

    @Test
    void coveringADesignNeverConfirmsItsParametersOrErasesANewExplanation() {
        var task = task(QUESTION, ANSWER);
        var sections = sections("研究尚未实施。", HEADER + "公平对照组的确认信息获取流程。", "不确认任何既有参数。");
        String novel = PREFIX + QUESTION + " 用户说明：必须使用2025年版本。";
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(novel), task)).containsExactly(novel);
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "公平对照组采用2025年版本。")).isFalse();
        var noState = sections("研究尚未实施。", HEADER + "公平对照组的确认信息获取流程。", "评分方法未确认。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(noState, List.of(PREFIX + QUESTION), task))
                .containsExactly(PREFIX + QUESTION);
    }

    @Test
    void sameMethodAndPartialOrFutureConfirmationDoNotResolveASeparateParameter() {
        var sections = sections("研究尚未实施。", HEADER + "公平对照组的确认信息获取流程。", "不确认任何既有参数。");
        for (String answer : List.of("确认评分方法，确认信息获取流程未确定。",
                "如果以后确认信息来源，再提出方案。", "暂不确定。")) {
            assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task(QUESTION, answer)))
                    .containsExactly(PREFIX + QUESTION);
        }
        var other = task("乙组的确认信息如何取得？", ANSWER);
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + "乙组的确认信息如何取得？"), other))
                .containsExactly(PREFIX + "乙组的确认信息如何取得？");
    }

    @Test
    void namedYearAndInstitutionMustBeFullyMatched() {
        String question = "甲院2024年公平对照组的确认信息如何取得？";
        var task = task(question, ANSWER);
        var matching = sections("研究尚未实施。", HEADER + "甲院2024年公平对照组的确认信息获取流程。", "不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(matching, List.of(PREFIX + question), task)).isEmpty();
        for (String other : List.of("甲院2025年", "乙院2024年")) {
            var foreign = sections("研究尚未实施。", HEADER + other + "公平对照组的确认信息获取流程。", "不确认任何既有参数。");
            assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(foreign, List.of(PREFIX + question), task)).containsExactly(PREFIX + question);
        }
    }

    @Test
    void examplesAndAcceptanceCannotReplaceTheDesignObligation() {
        var task = task(QUESTION, ANSWER);
        var sections = sections("研究尚未实施。", "撰写研究设计。", "不确认任何既有参数。");
        sections.put(PromptSectionType.EXAMPLES, new PromptSection(PromptSectionType.EXAMPLES, "示例", HEADER + "公平对照组的确认信息获取流程。"));
        sections.put(PromptSectionType.ACCEPTANCE, new PromptSection(PromptSectionType.ACCEPTANCE, "验收", HEADER + "公平对照组的确认信息获取流程。"));
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).containsExactly(PREFIX + QUESTION);
    }

    @Test
    void aFencedDesignMentionDoesNotSatisfyTheCurrentTask() {
        var task = task(QUESTION, ANSWER);
        var sections = sections("研究尚未实施。", "```text\n" + HEADER + "公平对照组的确认信息获取流程。\n```", "不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).containsExactly(PREFIX + QUESTION);
    }

    /** 保留实际题目ID及原回答，不用短答案或自造确认取代未决状态。 */
    private com.promptoptimizer.enhancement.domain.EffectiveTaskView task(String question, String answer) {
        return EffectiveTaskViewFactory.create("撰写中文研究设计的方法部分。", TemplateCode.AUTO,
                List.of(new PlanAnswer("q-current", question, answer)), List.of(), false);
    }

    /** 示例和验收没有权威覆盖来源，所有判断都经过当前四要素段落。 */
    private EnumMap<PromptSectionType, PromptSection> sections(String background, String task, String constraints) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", background));
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", task));
        result.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "交付中文方法正文。"));
        result.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints));
        return result;
    }
}
