package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 重放候选六的受众预设和委托边界，保留重要选择、来源用途及新条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ResearchPlanningBoundaryRegressionTest {
    private static final String RAW = "为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。"
            + "当前只是拟定研究，尚未实施。两名独立评审盲评。说明准确性、完整性、清晰度与可执行性评价以及分歧处理。";
    private static final String OBSERVED = "参与生成或回答的研究生对象如何确定？";

    @Test void observedAudiencePresuppositionUsesTheExistingPlanRepairPath() {
        var calls = new AtomicInteger();
        var clock = Clock.systemUTC();
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock),
                request -> new ContextSnapshot(request.customDescription(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), "test"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
        var service = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse("确认研究参与方式",
                List.of(question(calls.incrementAndGet() == 1 ? OBSERVED : "是否招募研究生参与生成或回答？")),
                "mock", "planner", true), new PromptTemplateRegistryImpl(), sessions, clock);

        var result = service.plan(new OptimizationPlanRequest(RAW, "", List.of()));
        assertThat(calls).hasValue(2);
        assertThat(result.questions()).singleElement().satisfies(question ->
                assertThat(question.question()).isEqualTo("是否招募研究生参与生成或回答？"));
    }

    @Test void currentQuestionIsRejectedButAnOpenPopulationChoiceIsKept() {
        var policy = PlanningDecisionPolicy.from(input(RAW, "", null));
        assertThatThrownBy(() -> policy.validateCandidate("", OBSERVED, "questions.hint"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.TASK_ROLE_CONFLICT);
                    assertThat(failure.getField()).isEqualTo("questions.question");
                });
        for (String question : List.of("是否招募研究生参与生成或回答？", "本研究是否招募研究生参与生成或回答？",
                "如果招募研究生，如何确定参与者范围？", "是否由研究生回答Plan问题，还是由研究者提供？",
                "建议以研究生作为参与者是否可行？", "参与生成或回答的对象如何确定？", "研究生读者需要怎样的方法说明？")) {
            assertThatCode(() -> policy.validateCandidate("", question, "questions.hint")).doesNotThrowAnyException();
        }
    }

    @Test void anExplicitUserTopicOrBoundPopulationChoiceSupportsTheSameAudience() {
        assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW + "研究对象为研究生。", "", null))
                .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        for (String role : List.of("本研究对象", "本次研究对象")) {
            assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW + role + "为研究生。", "", null))
                    .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        }
        for (String population : List.of("研究生和工程师", "研究生及工程师", "工程师与研究生")) {
            assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW + "研究对象为" + population + "。", "", null))
                    .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        }
        var view = EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO,
                List.of(new PlanAnswer("population", "研究对象选择哪些人群？", "本次选择研究生。")), List.of(), false);
        var request = new PlanningProviderRequest(RAW, "", List.of(), null, null, List.of(), "", view);
        assertThatCode(() -> PlanningDecisionPolicy.from(request).validateCandidate("", OBSERVED, "questions.hint"))
                .doesNotThrowAnyException();
    }

    @Test void explicitCurrentContextAndAnApplicableMaterialCanSupportPopulation() {
        assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW, "本研究的研究对象为研究生。", null))
                .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW, "研究方案：\n本研究的研究对象为研究生。", null))
                .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        for (var origin : List.of(PlanningFactOrigin.USER_MATERIAL, PlanningFactOrigin.PROJECT_DOCUMENT)) {
            assertThatCode(() -> PlanningDecisionPolicy.from(input(RAW, "", digest(origin, "本研究的研究对象为研究生。")))
                    .validateCandidate("", OBSERVED, "questions.hint")).doesNotThrowAnyException();
        }
    }

    @Test void negativesFutureConditionsAnotherStudyAndExamplesDoNotConfirmPopulation() {
        for (String evidence : List.of("本研究的研究对象不是研究生。", "如果以后确定研究对象为研究生，再制定招募计划。",
                "甲研究的研究对象为研究生。本研究的研究对象为工程师。", "示例：本研究的研究对象为研究生。",
                "本研究尚未确定研究对象是否为研究生。", "建议本研究的研究对象为研究生。",
                "本研究的研究对象为研究生，待确认后采用。", "示例：\n本研究的研究对象为研究生。",
                "甲研究：\n研究对象为研究生。\n本研究的研究对象为工程师。",
                "如果以后确认：\n研究对象为研究生。")) {
            assertThatThrownBy(() -> PlanningDecisionPolicy.from(input(RAW, evidence, null))
                    .validateCandidate("", OBSERVED, "questions.hint")).isInstanceOf(ProviderResponseValidationException.class);
        }
        assertThatThrownBy(() -> PlanningDecisionPolicy.from(input(RAW, "",
                digest(PlanningFactOrigin.TEST_FIXTURE, "本研究的研究对象为研究生。")))
                .validateCandidate("", OBSERVED, "questions.hint")).isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> PlanningDecisionPolicy.from(input(RAW + "\n示例：\n研究研究生的学习方法。", "", null))
                .validateCandidate("", OBSERVED, "questions.hint")).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test void sameReviewerDisagreementCanUseTheObservedAlternativeWording() {
        assertThat(filter(question("两名评审评分不一致时如何处理？"), RAW)).isEmpty();
        assertThat(filter(question("评分不一致时如何处理？"), RAW)).isEmpty();
        assertThat(filter(question("两名评审评分出现分歧时如何处理？"), RAW)).isEmpty();
    }

    @Test void otherObjectsNewConditionsAndReservedChoiceSurviveTheWholeQuestionCheck() {
        for (String text : List.of("数据来源不一致时如何处理？", "两名研究者对纳入标准的意见不一致时如何处理？",
                "两名评审评分不一致且差异超过2级时是否增加第三位评审？")) {
            var question = question(text);
            assertThat(filter(question, RAW)).containsExactly(question);
        }
        var disagreement = question("两名评审评分不一致时如何处理？");
        assertThat(filter(disagreement, RAW + "分歧处置方式由我决定，请先让我确认。")).containsExactly(disagreement);
        var withHint = new PlanQuestion("q", disagreement.question(), "此外，评分差异超过2级时是否增加第三位评审？",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(filter(withHint, RAW)).containsExactly(withHint);
        var conditionalHint = new PlanQuestion("q", disagreement.question(), "评分差异超过2级时是否增加第三位评审？",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(filter(conditionalHint, RAW)).containsExactly(conditionalHint);
        var withOption = new PlanQuestion("q", disagreement.question(), "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("a", "另请评审", "另需确认跨机构授权", "由第三位评审裁决", false)), List.of(), true);
        assertThat(filter(withOption, RAW)).containsExactly(withOption);
    }

    @Test void delegatedConcreteRecommendationsDoNotBecomeConfirmedParameterValues() {
        String instruction = TaskMeaningContract.from(RAW, List.of()).evaluationGuidance();
        assertThat(instruction).contains("已委托设计", "具体拟议建议", "适用条件", "未确认的实际选值仍保持未决")
                .doesNotContain("不代填未知评分尺度、参数");
    }

    private List<PlanQuestion> filter(PlanQuestion question, String raw) {
        return new PlanQuestionFilter().filter(List.of(question), input(raw, "", null));
    }

    private PlanQuestion question(String text) {
        return new PlanQuestion("q", text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private PlanningProviderRequest input(String raw, String context, PlanningContextDigest digest) {
        return new PlanningProviderRequest(raw, context, List.of(), digest);
    }

    private PlanningContextDigest digest(PlanningFactOrigin origin, String evidence) {
        return new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 1, List.of(),
                List.of(new PlanningFactCard("population", PlanningFactCategory.POPULATION, origin, "study.md", evidence)));
    }
}
