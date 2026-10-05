package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PromptOptimizationGuidance;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对真实改写提醒与新对象、新条件的区别，保留指南事实边界及推荐依据。
 * 夹具只来自已归档的合成任务，不调用外部模型，不改变原始失败记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenExpressionRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mergesThreeActualParaphrasesIntoTheirOriginalPendingDecisionsWithoutLosingExplanations() throws Exception {
        var replay = replay();
        List<PlanAnswer> answers = mapper.convertValue(replay.path("answers"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanAnswer.class));
        List<String> reminders = mapper.convertValue(replay.path("reminders"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        var merged = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers))
                .merge(reminders, List.of(), List.of());
        assertThat(merged.executionPrerequisites()).hasSize(3)
                .anyMatch(value -> value.contains("最大提问轮次") && value.contains("信息获取边界"))
                .anyMatch(value -> value.contains("分歧") && value.contains("评分流程可复核"))
                .anyMatch(value -> value.contains("评分锚点") && value.contains("统一评分口径"));
    }

    @Test
    void retainsNewObjectConditionNumberAndAnotherPendingAttribute() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("approval", "订单审批标准应如何确定？", "暂不确定")));
        for (String newDecision : List.of("退款审批标准尚未决定，需说明退款范围。",
                "订单审批标准在跨境时尚未决定，需说明范围。",
                "订单审批标准金额>=3000元时尚未决定，需说明范围。",
                "订单审批标准和有效期尚未决定，需说明范围。")) {
            var result = new PlanAmbiguityMerger(decisions).merge(List.of(newDecision), List.of(), List.of());
            assertThat(result.executionPrerequisites()).as(newDecision).hasSize(2)
                    .anyMatch(value -> value.equals(newDecision));
        }
    }

    @Test
    void keepsAnUnrelatedConfirmedValueAndAnAdditionalNewDecisionVisible() throws Exception {
        var replay = replay();
        List<PlanAnswer> answers = mapper.convertValue(replay.path("answers"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanAnswer.class));
        var decisions = ConfirmedDecisionSet.from(answers);
        for (String newFinding : List.of(
                "评分表采用连续尺度已确认，但具体等级和评分锚点仍未确定，需在定稿前明确。",
                "交互式计划确认组的最大提问轮次和终止规则尚未决定，另外需确认新增退款审批阈值。")) {
            assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(newFinding), List.of(), List.of())
                    .executionPrerequisites()).anyMatch(value -> value.contains(newFinding));
        }
    }

    @Test
    void preservesEveryAttributeWhenOnlyOnePartOfACompoundReminderRepeats() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("rounds",
                "计划确认组的最大提问轮次应如何预注册？", "暂不确定")));
        var finding = "计划确认组的最大提问轮次和终止规则尚未决定，需在预注册前明确。";
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(finding), List.of(), List.of())
                .executionPrerequisites()).hasSize(2).contains(finding);
    }

    @Test
    void lengthGuidancePreservesHardLimitsAndDoesNotForceAParagraphCountOrLeakACountReport() {
        assertThat(PromptOptimizationGuidance.ENHANCEMENT).contains("原有长度单位、上下限、固定段数",
                "长度要求作用于用户要交付的作品", "只输出成品时不附计数过程", "不能改变译文、纯JSON或固定格式");
        assertThat(TaskDeliveryProfile.NEWS_RELEASE.outputGuidance()).contains("长度下限和上限", "不增加固定段数", "用户原有边界")
                .doesNotContain("必须五段", "必须5段");
        assertThat(TaskDeliveryProfile.TRANSLATION.outputGuidance()).doesNotContain("区间中部", "反馈方式");
    }

    @Test
    void doesNotTurnUnverifiedGuideCapabilitiesIntoUserChoices() throws Exception {
        var replay = replay();
        List<PlanQuestion> questions = mapper.convertValue(replay.path("guideQuestions"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
        var input = new PlanningProviderRequest(replay.path("guideRawPrompt").asText(), "", List.of());
        assertThat(new PlanQuestionFilter().filter(questions, input)).isEmpty();
    }

    @Test
    void retainsExplicitRequestToConfirmFactsAndNewPublicationAuthorization() {
        var question = new PlanQuestion("guide", "资料未说明历史恢复功能，指南应如何表述？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("unknown", "保持未核实", "", "保持未核实", false),
                        new PlanOption("restore", "支持恢复", "", "支持恢复", false)), List.of(), true);
        var explicit = new PlanningProviderRequest("编写当前产品用户指南，不编造能力。请先向我确认资料未说明的历史恢复功能。", "", List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(question), explicit)).containsExactly(question);
        var additional = new PlanQuestion(question.id(), question.question(), "另外需要确认指南公开发布是否经过业务负责人批准。",
                question.type(), question.options(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(additional), new PlanningProviderRequest(
                "编写当前产品用户指南，资料未说明的能力保持未核实，不编造。", "", List.of()))).containsExactly(additional);
    }

    @Test
    void neverRecommendsTechnologyMentionedOnlyInAnUnselectedConditionalBranchOrSourcePath() {
        var question = choice("Vue 3", "React");
        for (String evidence : List.of("若批准迁移方案，则采用 Vue 3；该方案尚未决定。", "docs/Vue 3/候选方案.md",
                "未来可考虑 Vue 3，目前尚未采用。")) {
            var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(evidence), "COMPLETE", 1, List.of());
            var aligned = PlanRecommendationAligner.align(question, new PlanningProviderRequest("为页面兼容性选择框架方案", "", List.of(), digest));
            assertThat(aligned.options()).as(evidence).noneMatch(PlanOption::recommended);
        }
    }

    @Test
    void keepsExplicitCurrentPreferenceAndExactConditionalBusinessRuleSupported() {
        assertThat(PlanRecommendationAligner.align(choice("Vue 3", "React"), new PlanningProviderRequest(
                "本次迁移到 Vue 3，现有 React 仅是迁出来源。", "", List.of())).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::label).containsExactly("Vue 3");
        var query = new PlanQuestion("empty_region", "地区为空时如何处理？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("skip", "不查询", "", "当地区为空时不查询候选。", false),
                        new PlanOption("query", "查询", "", "当地区为空时查询候选。", false)), List.of(), true);
        assertThat(PlanRecommendationAligner.align(query, new PlanningProviderRequest(
                "当地区为空时不查询候选。", "", List.of())).options()).filteredOn(PlanOption::recommended)
                .extracting(PlanOption::id).containsExactly("skip");
    }

    @Test
    void sharedDeliverySentenceCannotChooseHowFactsAreOrganized() throws Exception {
        var followup = followup();
        var question = mapper.convertValue(followup.path("weakRecommendation"), PlanQuestion.class);
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                List.of("信息量匹配对照组获得已确认事实，再生成最终提示词。"), "COMPLETE", 1, List.of());
        assertThat(PlanRecommendationAligner.align(question, new PlanningProviderRequest(
                "请明确同等信息条件下的实验方案", "", List.of(), digest)).options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void repeatingTheExactQuestionAndAnswerDoesNotCreateADuplicateExplanation() {
        String question = "两名评审的一致性评价应采用哪种方式？";
        String answer = "当前尚未决定，不得默认补全。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("agreement", question, answer)));
        for (String repeat : List.of(question + answer,
                "两名评审的一致性评价应采用哪种方式尚未确定，不得默认补全。")) {
            var result = new PlanAmbiguityMerger(decisions).merge(List.of(repeat), List.of(), List.of());
            assertThat(result.executionPrerequisites()).hasSize(1).allSatisfy(value ->
                    assertThat(value).doesNotContain("补充说明"));
        }
        String newCondition = question + "另外需确认第三名评审的仲裁授权。";
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(newCondition), List.of(), List.of())
                .executionPrerequisites()).hasSize(2).contains(newCondition);
    }

    @Test
    void delegatesUnverifiedInterfaceDetailsToTheAlreadySpecifiedGuideFallback() throws Exception {
        var followup = followup();
        List<PlanQuestion> questions = mapper.convertValue(followup.path("guideQuestions"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
        var input = new PlanningProviderRequest(followup.path("guideRaw").asText(), "", List.of());
        assertThat(new PlanQuestionFilter().filter(questions, input)).isEmpty();
        var explicit = new PlanningProviderRequest(input.rawPrompt() + "\n请先向我确认未说明的具体功能，再写指南。", "", List.of());
        assertThat(new PlanQuestionFilter().filter(questions, explicit)).containsExactlyElementsOf(questions);
    }

    @Test
    void replacesOnlyTheSameCompleteConflictPresentationAndKeepsNewChoices() throws Exception {
        var followup = followup();
        List<PlanQuestion> questions = mapper.convertValue(followup.path("comparisonQuestions"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
        var order = PlanningConflictIdentity.parse(questions.get(0).question()).orElseThrow();
        var refund = PlanningConflictIdentity.parse(questions.get(1).question()).orElseThrow();
        assertThat(order.repeatsPresentationQuestion(questions.get(2))).isTrue();
        assertThat(refund.repeatsPresentationQuestion(questions.get(3))).isTrue();
        assertThat(order.repeatsPresentationQuestion(questions.get(3))).isFalse();
        assertThat(refund.repeatsPresentationQuestion(questions.get(2))).isFalse();
        var original = questions.get(2);
        for (String additional : List.of("另外需要确认退款标准。", "本次新增订单金额>=6000元须复核。",
                "本次需确认订单审批金额>3000元须复核。", "另需确认跨境订单的适用范围。")) {
            var options = new java.util.ArrayList<>(original.options());
            options.add(new PlanOption("additional", "附加决定", "", additional, false));
            assertThat(order.repeatsPresentationQuestion(new PlanQuestion(original.id(), original.question(), original.hint(),
                    original.type(), options, List.of(), true))).as(additional).isFalse();
        }
    }

    @Test
    void delegatesActualGuideCoverageQuestionsWithoutHidingAuthorizationOrAnExplicitFactCheck() throws Exception {
        var replay = finalFollowup();
        List<PlanQuestion> questions = mapper.convertValue(replay.path("guideQuestions"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
        String raw = replay.path("guideRaw").asText();
        assertThat(new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(raw, "", List.of()))).isEmpty();
        assertThat(new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(
                raw + "\n请先向我确认未说明的具体功能。", "", List.of()))).containsExactlyElementsOf(questions);
        for (String question : List.of("指南中资料未说明数据授权期限，应如何写明数据授权期限？",
                "指南中资料未说明公开发布的审批条件，应如何写明审批条件？",
                "指南中资料未说明患者的医学口径，应如何写明医学口径？")) {
            var independent = new PlanQuestion("independent", question, "需要确认独立业务依据。",
                    PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
            assertThat(new PlanQuestionFilter().filter(List.of(independent), new PlanningProviderRequest(raw, "", List.of())))
                    .as(question).containsExactly(independent);
        }
    }

    @Test
    void aConfirmedUnknownStatusCodeIsNotAnUnansweredPlanDecision() throws Exception {
        var answer = mapper.convertValue(finalFollowup().path("confirmedStatusAnswer"), PlanAnswer.class);
        var decisions = ConfirmedDecisionSet.from(List.of(answer));
        assertThat(decisions.pendingDecisions()).isEmpty();
        assertThat(decisions.knownDecisions()).extracting(ConfirmedPlanDecision::answer).containsExactly(answer.answer());
        for (String pending : List.of("UNKNOWN", "unknown", "当前为UNKNOWN，稍后确认。",
                "UNKNOWN不直接计为爽约，但观察窗口尚未决定。", "状态为 UNKNOWN 的预约应如何归因尚未确定。")) {
            assertThat(PlanAnswerSemantics.unresolved(pending)).as(pending).isTrue();
        }
    }

    @Test
    void anAlreadyBoundHospitalQuestionDoesNotReappearInsideItsExplanation() throws Exception {
        var replay = finalFollowup();
        var answer = mapper.convertValue(replay.path("hospitalAnswer"), PlanAnswer.class);
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(answer)))
                .merge(List.of(replay.path("hospitalReminder").asText()), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(1).allSatisfy(value -> {
            assertThat(value).contains("该窗口决定", "不应将尚未到期的预约算入爽约率");
            assertThat(value.substring(value.indexOf("补充说明："))).doesNotContain("应如何定义", answer.answer());
        });
    }

    @Test
    void repeatsOfACompletePartialAnswerDoNotDuplicateTheKnownAndPendingParts() throws Exception {
        var replay = finalFollowup();
        var answer = mapper.convertValue(replay.path("partialAnswer"), PlanAnswer.class);
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(answer)))
                .merge(List.of(replay.path("partialReminder").asText()), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(1).allSatisfy(value ->
                assertThat(value).doesNotContain("补充说明", "已确认采用顺序尺度"));
    }

    private com.fasterxml.jackson.databind.JsonNode finalFollowup() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/open-expression-final-followup-2026-10-05.json")) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream);
        }
    }

    @Test
    void mentioningAppointmentEventsDoesNotChooseTheStatisticalUnit() throws Exception {
        var replay = nounEvidence();
        var question = mapper.convertValue(replay.path("unitQuestion"), PlanQuestion.class);
        assertThat(PlanRecommendationAligner.align(question, new PlanningProviderRequest(
                replay.path("hospitalRaw").asText(), "", List.of())).options()).noneMatch(PlanOption::recommended);
        assertThat(PlanRecommendationAligner.align(question, new PlanningProviderRequest(
                "本次选择预约事件，统计渠道操作量。", "", List.of())).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("event_unit");
    }

    @Test
    void guideMentionAndCoverageChoicesInheritTheMaterialAndUserBoundaries() throws Exception {
        var replay = nounEvidence();
        List<PlanQuestion> questions = mapper.convertValue(replay.path("guideQuestions"),
                mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
        assertThat(new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(
                replay.path("guideRaw").asText(), "", List.of()))).isEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode nounEvidence() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/open-expression-noun-evidence-2026-10-05.json")) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode followup() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/open-expression-followup-2026-10-05.json")) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream);
        }
    }

    private PlanQuestion choice(String first, String second) {
        return new PlanQuestion("framework", "如何选择框架？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("first", first, "", "采用 " + first, true),
                        new PlanOption("second", second, "", "采用 " + second, false)), List.of(), true);
    }

    private com.fasterxml.jackson.databind.JsonNode replay() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/open-expression-2026-10-05.json")) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream);
        }
    }
}
