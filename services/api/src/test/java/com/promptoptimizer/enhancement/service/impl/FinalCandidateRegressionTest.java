package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回放已归档的真实模型首轮失败；不调用外部模型，也不以成功重跑替换失败样本。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class FinalCandidateRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanQuestionFilter filter = new PlanQuestionFilter();

    @Test
    void shouldKeepDifferentPendingQuestionsWhenBothAnswersOnlySayCurrentlyUndecided() {
        var decisions = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("plan_rounds", "研究最多进行几轮？", "当前尚未决定，不得默认补全。"),
                new PlanAnswer("reviewer_disagreement", "评审意见不一致时按什么标准处理？", "当前尚未决定，不得默认补全。")));
        assertThat(decisions.pendingDecisions()).extracting(value -> value.question())
                .containsExactly("研究最多进行几轮？", "评审意见不一致时按什么标准处理？");
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(), List.of(), List.of());
        assertThat(merged.messages()).hasSize(2).anyMatch(value -> value.contains("研究最多进行几轮"))
                .anyMatch(value -> value.contains("评审意见不一致"));
        assertThat(merged.executionPrerequisites()).hasSize(2);
    }

    @Test
    void shouldDistinguishBareStatusWordsFromAnActuallyNamedPendingBusinessObject() {
        for (String status : List.of("当前", "目前", "现在", "本次", "这次")) {
            assertThat(PlanAnswerSemantics.namesPendingSubject(status + "尚未决定，不得默认补全。"))
                    .as(status).isFalse();
        }
        assertThat(PlanAnswerSemantics.namesPendingSubject("当前退款标准尚未决定。")).isTrue();
        assertThat(PlanAnswerSemantics.namesPendingSubject("甲医院当前评分尺度尚未决定。")).isTrue();
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("scale_and_tool",
                "评分尺度与工具如何选择？", "评分尺度采用顺序尺度。甲医院分析工具尚未决定。")));
        assertThat(decisions.pendingDecisions()).hasSize(1).allSatisfy(value ->
                assertThat(value.question()).contains("甲医院分析工具"));
    }

    @Test
    void shouldKeepOnePendingReminderWithoutAppendingTheSameReviewedAnswerAgain() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/final-candidate-reminders-2026-10-05.json")) {
            var replay = mapper.readTree(stream);
            var answerType = mapper.getTypeFactory().constructCollectionType(List.class, PlanAnswer.class);
            var findingType = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
            var decisions = ConfirmedDecisionSet.from(mapper.convertValue(replay.path("answers"), answerType));
            List<String> findings = mapper.convertValue(replay.path("findings"), findingType);
            var merged = new PlanAmbiguityMerger(decisions).merge(findings, List.of(), List.of());
            assertThat(merged.messages()).hasSize(3).allSatisfy(value -> assertThat(value).doesNotContain("补充说明："));
            assertThat(merged.executionPrerequisites()).anyMatch(value -> value.contains("不默认固定一轮或无限轮"))
                    .anyMatch(value -> value.contains("评分变量类型"));
            String newCondition = findings.getFirst() + " 另外需确认新增机构的退款金额>=3000元是否先审批。";
            assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(newCondition), List.of(), List.of()).executionPrerequisites())
                    .anyMatch(value -> value.contains("退款金额>=3000元"));
        }
    }

    @Test
    void shouldAllowAnUnselectedProposalButStillRejectAnUnsupportedKnownFact() {
        var guard = new RequirementFidelityGuard();
        var validation = guard.prepare(guard.explicitRules("评分尺度尚未确定，必须保留未知，不得默认指定尺度。", List.of()));
        validation.validateProposal("评分尺度采用順序尺度。", "questions.options.answer");
        assertThatThrownBy(() -> validation.validate("评分尺度采用順序尺度。", "sections.BACKGROUND"))
                .isInstanceOf(ProviderResponseValidationException.class);
        var cancellation = guard.prepare(guard.explicitRules("用户点击取消时必须保持表单原值，不得修改表单。", List.of()));
        assertThatThrownBy(() -> cancellation.validateProposal("用户点击取消时清空表单原值。", "questions.options.answer"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldNotAskKnownGuideButtonFallbackCopyCoverageOrHistoryEntryAgain() throws Exception {
        var replay = replay("TT-GUIDE-L", "final-candidate-first-failures-2026-10-05.json");
        assertThat(filter.filter(replay.questions(), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .isEmpty();
        var original = replay.questions().get(1);
        var newCondition = new PlanQuestion(original.id(), original.question(), "还需确认复制到外部系统是否需要新增脱敏权限。",
                original.type(), original.options(), List.of(), true);
        assertThat(filter.filter(List.of(newCondition), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .containsExactly(newCondition);
    }

    @Test
    void shouldInheritSpecifiedHospitalDeliverablesWhileRetainingUnknownFieldMapping() throws Exception {
        var replay = replay("TT-HOSPITAL-M", "final-candidate-first-failures-2026-10-05.json");
        assertThat(filter.filter(replay.questions(), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .extracting(PlanQuestion::id).containsExactly("hospital_b_format", "hospital_b_status_mapping",
                        "hospital_b_fields", "cross_hospital_comparison_scope");
        var original = replay.questions().getLast();
        var newCondition = new PlanQuestion(original.id(), original.question(), "另外需确定第三家医院共享字段的授权范围。",
                original.type(), original.options(), List.of(), true);
        assertThat(filter.filter(List.of(newCondition), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .containsExactly(newCondition);
    }

    @Test
    void shouldInheritGuideBoundariesInsteadOfAskingFiveRoutinePresentationQuestions() throws Exception {
        var replay = replay("TT-GUIDE-L");
        assertThat(filter.filter(replay.questions(), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .isEmpty();
    }

    @Test
    void shouldKeepHospitalUnknownsButNotReaskTheKnownSharingRestriction() throws Exception {
        var replay = replay("TT-HOSPITAL-M");
        assertThat(filter.filter(replay.questions(), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .extracting(PlanQuestion::id).containsExactly("hospital_b_format", "hospital_b_status_mapping");
        var sharing = replay.questions().getLast();
        var newScope = new PlanQuestion(sharing.id(), sharing.question(), "另外需确定第三家医院共享字段的授权范围。",
                sharing.type(), sharing.options(), List.of(), true);
        assertThat(filter.filter(List.of(newScope), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .containsExactly(newScope);
        assertThat(filter.filter(List.of(sharing), new PlanningProviderRequest(replay.rawPrompt()
                .replace("比较甲医院与乙医院的预约登记完整性", "分别整理甲医院与乙医院的预约登记"), "", List.of())))
                .containsExactly(sharing);
    }

    @Test
    void shouldKeepUserRequestedGuideChoicesAndNewPublishingDecisions() throws Exception {
        var replay = replay("TT-GUIDE-L");
        var button = replay.questions().getFirst();
        assertThat(filter.filter(List.of(button), new PlanningProviderRequest(
                replay.rawPrompt() + "请先让我选择按钮名称的处理方式。", "", List.of())))
                .containsExactly(button);
        var approval = new PlanQuestion("publication", "指南发布前由哪个部门审核？", "",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(filter.filter(List.of(approval), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .containsExactly(approval);
        var original = replay.questions().getFirst();
        var compound = new PlanQuestion(original.id(), original.question(), "另外需确定指南发布的法域和审核权限。",
                original.type(), original.options(), List.of(), true);
        assertThat(filter.filter(List.of(compound), new PlanningProviderRequest(replay.rawPrompt(), "", List.of())))
                .containsExactly(compound);
    }

    @Test
    void shouldNotTurnAnUnverifiedFeatureIntoKnownAbsence() {
        var guard = new RequirementFidelityGuard();
        var rules = guard.explicitRules("资料未说明自动撤销功能，必须保持未核实状态，不得断言该功能不存在。", List.of());
        assertThatThrownBy(() -> guard.validate("平台没有自动撤销功能。", rules, "sections.BACKGROUND"))
                .isInstanceOf(ProviderResponseValidationException.class);
        // 另一功能、明确条件和否定引用不是对未知功能的存在性断言。
        guard.validate("扫描PDF不支持OCR；自动撤销功能未核实。", rules, "sections.BACKGROUND");
        guard.validate("如果自动撤销功能不存在，需要说明替代步骤。", rules, "sections.TASK");
        guard.validate("不得因为资料缺失而认定平台没有自动撤销功能。", rules, "sections.CONSTRAINTS");
        guard.validate("平台没有自动撤销功能。", guard.explicitRules("平台明确没有自动撤销功能。", List.of()), "sections.BACKGROUND");
    }

    @Test
    void shouldNotChooseAnUnknownProfessionalScaleWhilePreservingSeparateParameters() {
        var guard = new RequirementFidelityGuard();
        var rules = guard.explicitRules("评分尺度尚未确定，必须保留未知，不得默认指定尺度。", List.of());
        assertThatThrownBy(() -> guard.validate("评分尺度采用顺序尺度。", rules, "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        guard.validate("评分尺度尚未确定，待选定后再选择分析方法。", rules, "sections.TASK");
        guard.validate("评分尺度保留待定，工具明确采用R。", rules, "sections.TASK");
        guard.validate("如果用户选定顺序尺度，再评估相应统计方法。", rules, "sections.TASK");
    }

    @Test
    void shouldAcceptTheProfessionalScaleActuallyConfirmedAfterPlanning() {
        var guard = new RequirementFidelityGuard();
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("score_scale",
                "评分尺度使用哪种？", "本次评分尺度采用顺序尺度。")));
        var rules = guard.explicitRules("评分尺度尚未确定，必须保留未知，不得默认指定尺度。",
                decisions.decisions());
        guard.validate("本次评分尺度采用顺序尺度。", rules, "sections.TASK");
        assertThat(ResolvedPlanState.from(decisions, "评分尺度尚未确定。")
                .reconcile("评分尺度尚未确定。工具尚未确定。"))
                .contains("评分尺度已由用户确认选定", "工具尚未确定")
                .doesNotContain("评分尺度尚未确定");
    }

    @Test
    void shouldNotResolveAnotherObjectConditionalChoiceOrCurrentState() {
        var guard = new RequirementFidelityGuard();
        String raw = "甲医院评分尺度尚未确定，必须保留未知，不得默认指定尺度。";
        for (var answer : List.of(
                new PlanAnswer("scale_b", "乙医院评分尺度使用哪种？", "本次乙医院评分尺度采用顺序尺度。"),
                new PlanAnswer("scale_a", "甲医院评分尺度使用哪种？", "如果负责人批准，甲医院评分尺度采用顺序尺度。"),
                new PlanAnswer("scale_a", "甲医院当前评分尺度是什么？", "目前甲医院评分尺度采用顺序尺度。"))) {
            var decisions = ConfirmedDecisionSet.from(List.of(answer));
            assertThat(ResolvedPlanState.from(decisions, raw).reconcile(raw)).contains("甲医院评分尺度尚未确定");
            assertThatThrownBy(() -> guard.validate("甲医院评分尺度采用顺序尺度。",
                    guard.explicitRules(raw, decisions.decisions()), "sections.TASK"))
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void shouldResolveOnlyTheConfirmedParameterInAPartialAnswer() {
        var guard = new RequirementFidelityGuard();
        String raw = "评分尺度尚未确定。工具尚未确定。不得默认指定尺度或工具。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("scale_and_tool",
                "评分尺度使用哪种，工具使用哪种？", "本次评分尺度采用顺序尺度。工具暂不确定，不能自行指定。")));
        var rules = guard.explicitRules(raw, decisions.decisions());
        guard.validate("评分尺度采用顺序尺度。工具仍待确认。", rules, "sections.TASK");
        assertThatThrownBy(() -> guard.validate("工具采用R。", rules, "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThat(ResolvedPlanState.from(decisions, raw).reconcile(raw))
                .contains("评分尺度已由用户确认选定", "工具尚未确定");
    }

    @Test
    void shouldApplyAnExplicitChoiceInsideALongMarkdownAnswerWithoutResolvingOtherConditions() {
        String left = "ABC研究中心订单金额>50000元时进行二级复核";
        String right = "ABC研究中心订单金额>=50000元时进行二级复核";
        String conflict = "资料对“ABC研究中心”存在不同取值：docs/现行.md（" + left
                + "）与 docs/草案.md（" + right + "）";
        String answer = "我已逐项核对材料。".repeat(30) + "\n**我的最终决定是：**采用" + left
                + "。这只是本次交付采用的口径，不代表草案已签署。退款时限暂不确定，不得自行补造。";
        var identity = PlanningConflictIdentity.parse(conflict).orElseThrow();
        assertThat(identity.selectedValue(answer)).contains(PlanningConflictIdentity.canonical(left));
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-choice", conflict, answer)));
        String raw = left + "，" + right + "。当前尚未确认采用哪一版，不自行折中。\n退款时限尚未确定。";
        assertThat(ResolvedPlanState.from(decisions, raw).reconcile(raw))
                .contains("本次版本已按用户确认选定", "不自行折中", "退款时限尚未确定")
                .doesNotContain("当前尚未确认采用哪一版");
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(conflict), List.of(), List.of()).messages())
                .noneMatch(value -> value.equals(conflict));
        assertThat(identity.selectedValue("我的决定是：不采用" + left + "，退款时限采用7天。"))
                .isEmpty();
        assertThat(identity.selectedValue("我的决定是：如果经理批准，采用" + left + "。"))
                .isEmpty();
    }

    private Replay replay(String id) throws Exception {
        return replay(id, "tutorial-first-failures-2026-10-05.json");
    }

    private Replay replay(String id, String resource) throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/" + resource)) {
            for (var entry : mapper.readTree(stream)) {
                if (!id.equals(entry.path("id").asText())) continue;
                var type = mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class);
                return new Replay(entry.path("rawPrompt").asText(), mapper.convertValue(entry.path("questions"), type));
            }
        }
        throw new IllegalArgumentException("Missing synthetic replay case: " + id);
    }

    private record Replay(String rawPrompt, List<PlanQuestion> questions) { }
}
