package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 覆盖声明必须有当前来源与最终正文锚点；不以协议检查冒充语义或真实作品验收。 */
class NaturalTaskReviewTest {
    @Test
    void revisionFeedbackIsRequiredButPreviousAssistantTextIsNotPromotedToUserFact() {
        var revised = input().withRevision(new NaturalPromptRevision("错误的上一稿", List.of("保留时间"), "只修改语气"));
        assertThat(revised.taskSources()).anySatisfy(source -> {
            assertThat(source.id()).isEqualTo("revision.previousPrompt");
            assertThat(source.kind()).isEqualTo(NaturalTaskSource.Kind.ASSISTANT_CONVERSATION);
        });
        assertThatThrownBy(() -> task(review()).resolve(revised)).isInstanceOfSatisfying(
                NaturalTaskReview.MissingSources.class, failure -> assertThat(failure.sourceIds())
                        .containsExactlyInAnyOrder("revision.earlierFeedback.0", "revision.feedback"));
    }

    private static final String RAW = "请写一条通知，50字以内，只输出正文；若时间未定就写待定。";
    private static final String BODY = "请写一条50字以内的通知，只输出正文；活动时间未定，请写“待定”。";

    @Test
    void coversGoalFormatLengthIndependentConditionAndActualUnknownWithoutAddingFields() {
        var task = task(review()).resolve(input());
        task.review().validateEnhancement(task, BODY);
        task.review().validateEnhancement(task, BODY + "\n\n以下操作必须先获得人工确认：发送通知。");
        assertThat(task.additions()).isEmpty();
        assertThat(task.requirements().get(3).evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
    }

    @Test
    void refusesClaimedCoverageWhenTheDraftOrAssembledBodyHasLostItsEvidence() {
        var task = task(review()).resolve(input());
        assertThatThrownBy(() -> task.review().validateEnhancement(task, BODY.replace("50字以内", "200字以内")))
                .isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> task.review().validateEnhancement(task, BODY.replace("活动时间未定，请写“待定”", "活动于明天召开")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void cannotUseDuplicateOrOutOfRangeIndexesToHideAnUncoveredRequirement() {
        for (Integer index : List.of(-1, 0, 4)) {
            var coverage = new ArrayList<>(review().requirementCoverage());
            coverage.set(1, new NaturalTaskReview.Coverage(index, List.of("只输出正文")));
            var candidate = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of());
            assertThatThrownBy(() -> {
                var task = task(candidate).resolve(input());
                task.review().validateEnhancement(task, BODY);
            }).isInstanceOf(ProviderException.class);
        }
    }

    @Test
    void everyActualAnswerAndTheOriginalGoalNeedSourceAttribution() {
        var original = task(review());
        for (var omitted : List.of(original.requirements().subList(0, 3), original.requirements().subList(3, 4))) {
            var candidate = new NaturalTaskUnderstanding(original.goal(), original.deliverable(), omitted, List.of(), List.of(), review());
            assertThatThrownBy(() -> candidate.resolve(input())).isInstanceOf(ProviderException.class);
        }
    }

    @Test
    void usefulAdditionNeedsAnActualGapAndAPlaceInTheDeliveredBody() {
        var original = task(review());
        var additions = List.of(new NaturalTaskUnderstanding.Addition("语言简明", "方便收件人快速理解", "原文未说明表达方式"));
        var evidence = new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION,
                review().requirementCoverage(), List.of(new NaturalTaskReview.Coverage(0, List.of("语言简明"))), List.of());
        var task = new NaturalTaskUnderstanding(original.goal(), original.deliverable(), original.requirements(), additions, List.of(), evidence)
                .resolve(input());
        task.review().validateEnhancement(task, BODY + "语言简明。");
        assertThatThrownBy(() -> task.review().validateEnhancement(task, BODY)).isInstanceOf(ProviderException.class);
        var unjustified = List.of(new NaturalTaskUnderstanding.Addition("语言简明", "方便阅读"));
        assertThatThrownBy(() -> new NaturalTaskUnderstanding(original.goal(), original.deliverable(), original.requirements(),
                unjustified, List.of(), evidence).resolve(input())).isInstanceOf(ProviderException.class);
    }

    @Test
    void currentProtocolRequiresADimensionButOlderVersionDoesNotInventOneOrRequireAReview() {
        var original = task(review());
        var noDimension = original.requirements().stream()
                .map(requirement -> new NaturalTaskUnderstanding.Requirement(requirement.text(), requirement.evidence())).toList();
        var candidate = new NaturalTaskUnderstanding(original.goal(), original.deliverable(), noDimension, List.of(), List.of(), review());
        assertThatThrownBy(() -> candidate.resolve(input())).isInstanceOf(ProviderException.class);
        assertThat(candidate.resolve(input().withStrategyVersion("3")).requirements()).hasSize(4);
        assertThatThrownBy(() -> new NaturalTaskUnderstanding(original.goal(), original.deliverable(), original.requirements(),
                List.of(), List.of()).resolve(input())).isInstanceOf(ProviderException.class);
    }

    @Test
    void zeroQuestionPlanDoesNotPretendToHaveAFinalBody() {
        var zero = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of());
        task(zero).resolve(input()).review().validatePlan(Set.of());
        assertThatThrownBy(() -> task(review()).resolve(input()).review().validatePlan(Set.of())).isInstanceOf(ProviderException.class);
    }

    @Test
    void eachActualQuestionNeedsItsOwnReasonAndDifferentAnswerImpact() {
        var impact = new NaturalTaskReview.PlanImpact("q2", "用户保留是否公开发送的授权，现有输入无法确定", "公开发送需核对公开范围，内部发送只面向内部收件人");
        var review = new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION, List.of(), List.of(), List.of(impact));
        task(review).resolve(input()).review().validatePlan(Set.of("q2"));
        assertThatThrownBy(() -> task(review).resolve(input()).review().validatePlan(Set.of("q2", "q3"))).isInstanceOf(ProviderException.class);
        var duplicate = new NaturalTaskReview(review.inputReadiness(), List.of(), List.of(), List.of(impact, impact));
        assertThatThrownBy(() -> task(duplicate).resolve(input()).review().validatePlan(Set.of("q2"))).isInstanceOf(ProviderException.class);
        var noImpact = new NaturalTaskReview(review.inputReadiness(), List.of(), List.of(),
                List.of(new NaturalTaskReview.PlanImpact("q2", "", "")));
        assertThatThrownBy(() -> task(noImpact).resolve(input())).isInstanceOf(ProviderException.class);
    }

    @Test
    void diagnosticsCannotCarryCredentialsOrBorrowASpanFromAnotherDraft() {
        var bad = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                List.of(new NaturalTaskReview.Coverage(0, List.of("password=fixture-only-credential"))), List.of(), List.of());
        assertThatThrownBy(() -> task(bad).resolve(input())).isInstanceOf(ProviderException.class);
        var spans = new ArrayList<>(review().requirementCoverage());
        spans.set(0, new NaturalTaskReview.Coverage(0, List.of("另一份未交付正文中的句子")));
        var wrong = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, spans, List.of(), List.of());
        var task = task(wrong).resolve(input());
        assertThatThrownBy(() -> task.review().validateEnhancement(task, BODY)).isInstanceOf(ProviderException.class);
    }

    @Test
    void multilineOutputUsesSeveralSingleLineAnchorsWithoutChangingItsLayout() {
        var requirements = List.of(requirement("目标与格式", "rawPrompt", "请写一条通知", NaturalTaskUnderstanding.Dimension.GOAL));
        var input = new NaturalPromptInput(RAW, input().context(), List.of(), List.of(), List.of(), "fixture:model");
        var valid = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                List.of(new NaturalTaskReview.Coverage(0, List.of("请写一条通知", "只输出正文"))), List.of(), List.of());
        var task = new NaturalTaskUnderstanding("通知", "正文", requirements, List.of(), List.of(), valid).resolve(input);
        task.review().validateEnhancement(task, "请写一条通知\r\n只输出正文");
        for (String lineBreak : List.of("\n", "\r\n", "\u2028")) {
            var invalid = new NaturalTaskReview(valid.inputReadiness(),
                    List.of(new NaturalTaskReview.Coverage(0, List.of("请写一条通知" + lineBreak + "只输出正文"))), List.of(), List.of());
            assertThatThrownBy(() -> new NaturalTaskUnderstanding("通知", "正文", requirements, List.of(), List.of(), invalid).resolve(input))
                    .isInstanceOf(ProviderException.class);
        }
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q1", "活动时间？", "时间未定，保留待定")), List.of(), List.of(), "fixture:model");
    }

    private NaturalTaskUnderstanding task(NaturalTaskReview review) {
        return new NaturalTaskUnderstanding("撰写通知", "通知正文", List.of(
                requirement("通知目标", "rawPrompt", "请写一条通知", NaturalTaskUnderstanding.Dimension.GOAL),
                requirement("篇幅与格式", "rawPrompt", "50字以内，只输出正文", NaturalTaskUnderstanding.Dimension.FORMAT, NaturalTaskUnderstanding.Dimension.LENGTH),
                requirement("时间未定的条件", "rawPrompt", "若时间未定就写待定", NaturalTaskUnderstanding.Dimension.CONDITION),
                requirement("实际回答仍未确定时间", "actualAnswers.0", "时间未定，保留待定", NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)),
                List.of(), List.of(), review);
    }

    private NaturalTaskUnderstanding.Requirement requirement(String text, String source, String quote, NaturalTaskUnderstanding.Dimension... dimensions) {
        return new NaturalTaskUnderstanding.Requirement(text, List.of(new NaturalTaskUnderstanding.Evidence(source, quote)), List.of(dimensions));
    }

    private NaturalTaskReview review() {
        return new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(
                new NaturalTaskReview.Coverage(0, List.of("请写一条50字以内的通知")),
                new NaturalTaskReview.Coverage(1, List.of("50字以内的通知，只输出正文")),
                new NaturalTaskReview.Coverage(2, List.of("活动时间未定，请写“待定”")),
                new NaturalTaskReview.Coverage(3, List.of("活动时间未定，请写“待定”"))), List.of(), List.of());
    }
}
