package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskMeaningContract;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证委托提问、明确评价对象和共同表述归并在独立对象、新条件及未知状态下的边界。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateEightScopeRegressionTest {
    private static final String RAW = "为研究生撰写研究方法正文，研究Plan问答对提示词质量的影响。"
            + "当前研究尚未实施。说明准确性评价以及分歧处理。";

    @ParameterizedTest
    @ValueSource(strings = {"两名评审出现分歧时如何处理？", "两位独立评审出现分歧时如何处理？",
            "评审之间存在不一致时怎样复核？", "两名评审评分出现分歧时如何处理？", "评分分歧如何解决？"})
    void shouldDelegateOnlyTheRequestedReviewerDisagreement(String question) {
        var candidate = question(question, "", List.of(), List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(candidate), request())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"两家机构的数据来源分歧如何处理？", "其他研究的两名评审出现分歧时如何处理？",
            "两名评审出现分歧且需要剔除任务时如何处理？", "两名评审分差超过2分时如何处理？",
            "两名评审出现分歧后，是否允许第三方读取个人资料？", "评分分歧时是否改用另一指标？",
            "另一项研究的评分方式如何选择？", "新增指标的评分方式如何确定？"})
    void shouldKeepIndependentSubjectsAndNewConditions(String question) {
        var candidate = question(question, "", List.of(), List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(candidate), request())).containsExactly(candidate);
    }

    @Test
    void shouldKeepIndependentConditionsInHintsOptionsAndExamples() {
        var plain = "两名评审出现分歧时如何处理？";
        var candidates = List.of(question(plain, "另需确认第三方处理真实资料的授权。", List.of(), List.of()),
                question(plain, "", List.of(new PlanOption("opt", "跨机构裁决", "确认另一机构数据的使用权限。", "", false)), List.of()),
                question(plain, "", List.of(), List.of("分差超过2分时，是否剔除任务？")));
        for (var candidate : candidates)
            assertThat(new PlanQuestionFilter().filter(List.of(candidate), request())).containsExactly(candidate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"分别评价各模式生成的提示词，执行作品作为核对可执行性的依据。",
            "不得把评价对象写成提示词或对应产物。", "如果用户另行确认增加评价对象，再评价下游作品。"})
    void shouldAllowThePromptTargetItsEvidenceAndNegatedOrFutureAlternatives(String content) {
        assertThatCode(() -> TaskMeaningContract.from(RAW, List.of()).validate(content, "sections.TASK"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"评价对象为下游作品。", "评分对象是最终代码作品。", "各指标作用于提示词或对应产物。"})
    void shouldRejectAnUnapprovedSubstitutionOfTheKnownPromptTarget(String content) {
        assertThatThrownBy(() -> TaskMeaningContract.from(RAW, List.of()).validate(content, "sections.TASK"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.EVALUATION_TARGET_CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"研究Plan问答对提示词质量与下游作品质量的影响。",
            "研究提示词清晰度。研究下游作品准确性。主次尚未确定。",
            "示例：\n研究Plan问答对提示词质量的影响。", "如果以后获准，研究Plan问答对提示词质量的影响。",
            "另一研究：\n研究Plan问答对提示词质量的影响。"})
    void shouldNotInventASingleTargetForJointUncertainOrNonCurrentResearch(String raw) {
        assertThatCode(() -> TaskMeaningContract.from(raw, List.of()).validate(
                "评价对象为下游作品。", "sections.TASK")).doesNotThrowAnyException();
    }

    @Test
    void shouldApplyAnExplicitTargetChoiceWithoutBorrowingAnotherDecision() {
        var selected = new ConfirmedPlanDecision("target", "主要评价对象是什么？", "评价对象",
                ConfirmedPlanDecision.Scope.CHOICE, "主要评价下游作品，不评价提示词。");
        var meaning = TaskMeaningContract.from(RAW, List.of(selected));
        assertThat(meaning.evaluationTargets()).singleElement().satisfies(target -> {
            assertThat(target.text()).isEqualTo("下游作品");
            assertThat(target.sourceQuestionId()).isEqualTo("target");
        });
        assertThatCode(() -> meaning.validate("评分对象是下游作品。", "sections.TASK")).doesNotThrowAnyException();
        var other = new ConfirmedPlanDecision("material", "第三方是否允许读取评价对象的资料？", "资料授权",
                ConfirmedPlanDecision.Scope.CHOICE, "允许读取下游作品。");
        assertThatThrownBy(() -> TaskMeaningContract.from(RAW, List.of(other)).validate(
                "评分对象是下游作品。", "sections.TASK")).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldNotMakeFutureOrUnresolvedTargetChoicesCurrent() {
        for (var answer : List.of("如果以后确认主要评价下游作品，再调整。", "尚未确定。")) {
            var unknown = new ConfirmedPlanDecision("target", "主要评价对象是什么？", "评价对象",
                    ConfirmedPlanDecision.Scope.UNRESOLVED, answer);
            assertThatThrownBy(() -> TaskMeaningContract.from(RAW, List.of(unknown)).validate(
                    "评分对象是下游作品。", "sections.TASK")).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void shouldKeepEqualTextForIndependentBoundObjectsSeparate() {
        var task = EffectiveTaskViewFactory.create("分别明确甲指标和乙指标的计算要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("a", "甲指标采用哪个工具？", "使用R。"),
                        new PlanAnswer("b", "乙指标采用哪个工具？", "使用R。")), List.of(), false);
        assertThat(task.modelInput().executionRequirements()).hasSize(2);
        for (var requirement : task.modelInput().executionRequirements()) assertThat(requirement.sourceQuestionIds()).hasSize(1);
    }

    @Test
    void shouldKeepNonDelegatedUnknownsAndNewConditionsIndependent() {
        var task = EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO,
                List.of(new PlanAnswer("year", "标准人口年份是什么？", "尚未确定。"),
                        new PlanAnswer("threshold", "真实业务阈值是什么？", "如果以后确认阈值为2，再执行。")), List.of(), false);
        var payload = new ObjectMapper().valueToTree(task.modelInput());
        assertThat(payload.path("pendingDecisions")).hasSize(2);
        for (var value : payload.path("pendingDecisions")) assertThat(value.path("designDelegated").asBoolean()).isFalse();
        assertThat(task.pendingDecisions()).allSatisfy(value -> assertThat(value.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED));
    }

    private PlanningProviderRequest request() { return new PlanningProviderRequest(RAW, "", List.of(), null); }

    private PlanQuestion question(String text, String hint, List<PlanOption> options, List<String> examples) {
        return new PlanQuestion("q", text, hint, options.isEmpty() ? PlanQuestionType.FREE_TEXT : PlanQuestionType.SINGLE_CHOICE,
                options, examples, true);
    }
}
