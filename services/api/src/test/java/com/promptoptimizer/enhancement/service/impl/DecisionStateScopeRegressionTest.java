package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在最终组装入口核对资料未知、逐指标确认及可复制正文，防止只测试内部正则而遗漏共享链路。
 * 原证据、不同机构和假设分支均保留，测试不依赖真实凭据或专业默认口径。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DecisionStateScopeRegressionTest {
    private static final String RAW = "制定病案质量研究方案，保留完整性、格式异常与一致性指标表和必要伪代码。";

    @Test
    void resolvesOnlyTheConfirmedPartOfACoordinatedMaterialDeclaration() {
        var answers = List.of(new PlanAnswer("completeness", "缺失记录是否进入完整性指标的分母？",
                "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。"
                        + "仅确认完整性分母，一致性指标分母仍未决定。"));
        var evidence = List.of(fact("完整性指标分母与一致性指标分母尚需分别确定。"));
        var result = assemble("完整性指标分母采用所有有效出院记录。\n"
                + "| 指标 | 分母 | 状态 |\n|---|---|---|\n"
                + "| 完整性指标 | 所有有效出院记录 | 已确认 |\n"
                + "| 一致性指标 | 待确认 | 未决 |", RAW, answers, true, evidence);
        assertThat(result.optimizedPrompt()).contains("完整性指标分母采用所有有效出院记录")
                .doesNotContain("完整性指标分母与一致性指标分母尚需分别确定");
        assertThat(result.ambiguities()).noneMatch(value -> value.matches("完整性指标分母.*(?:未|待).*") );
        assertThatThrownBy(() -> assemble(table("一致性指标", "所有有效出院记录"), RAW, answers, true, evidence))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThat(evidence.getFirst().evidence()).isEqualTo("完整性指标分母与一致性指标分母尚需分别确定。");
        String future = "如果以后确认完整性指标分母与一致性指标分母尚需分别确定。";
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters(future, ConfirmedDecisionSet.from(answers).decisions()))
                .isEqualTo(future);
        String another = "乙院完整性指标分母与乙院一致性指标分母尚需分别确定。";
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters(another, ConfirmedDecisionSet.from(answers).decisions()))
                .isEqualTo(another);
    }

    @Test
    void rejectsAConcreteDenominatorDeclaredUnknownInTheUploadedMaterial() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录数"), RAW, List.of(), false,
                List.of(fact("一致性指标分母尚未确定。"))))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void appliesAnExplicitNamedChoiceEvenWhenTheQuestionUsesStatisticalDenominatorWording() {
        String material = "完整性指标分母与一致性指标分母尚需分别确定。";
        for (String question : List.of("完整性指标的统计分母应如何确定？", "本次采用哪些指标口径？")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator", question,
                    "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。"
                            + "一致性指标分母仍未决定。")));
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(material, decisions.decisions()))
                    .contains("完整性指标分母已按本次回答确认", "一致性指标分母尚未确定")
                    .doesNotContain("完整性指标分母与一致性指标分母尚需分别确定");
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(
                    "乙院完整性指标分母与乙院一致性指标分母尚需分别确定。", decisions.decisions()))
                    .isEqualTo("乙院完整性指标分母与乙院一致性指标分母尚需分别确定。");
        }
    }

    @Test
    void neverInfersANamedChoiceFromAnUnspecifiedOrFutureAnswer() {
        String material = "完整性指标分母与一致性指标分母尚需分别确定。";
        for (String answer : List.of("采用所有有效出院记录。", "如果以后确认完整性指标分母采用所有有效出院记录。")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator",
                    "完整性指标的统计分母应如何确定？", answer)));
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(material, decisions.decisions()))
                    .isEqualTo(material);
        }
    }

    @Test
    void compactsOnlyTheCoveredDenominatorAndImputationRequestsAndKeepsNewYearAndMethod() {
        var decisions = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("consistency", "一致性指标分母如何确定？",
                        "一致性指标分母尚未确定，后续须按各逻辑规则的适用记录核实。"),
                new PlanAnswer("imputation", "缺失值是否插补、适用指标和方法如何确定？",
                        "是否插补、适用指标和方法均未决定。")));
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(
                "一致性指标分母如何确定？需按各逻辑规则的适用记录分别核实并确定。"
                        + "乙院2026年规则仍需独立审批。",
                "缺失值是否插补、适用指标和方法如何确定？需明确插补适用指标和方法。"
                        + "如果以后批准多重插补，须另定适用年份。"), List.of(), List.of());
        String text = String.join("\n", merged.executionPrerequisites());
        assertThat(text).contains("后续须按各逻辑规则的适用记录核实", "是否插补、适用指标和方法均未决定",
                        "乙院2026年规则仍需独立审批", "如果以后批准多重插补", "适用年份")
                .doesNotContain("需按各逻辑规则的适用记录分别核实并确定", "需明确插补适用指标和方法");
    }

    @Test
    void compactsBoundImputationExplanationsWithoutDiscardingANewMethodOrCondition() {
        String question = "缺失值是否插补、适用指标和方法如何确定？";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("imputation", question,
                "是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响，"
                        + "不把默认不插补写成已确认专业决定。")));
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(question
                + "且不同指标可能采用不同处理。需确认是否插补、适用指标及方法，不得默认统一插补。"
                + "未确认前保持缺失状态并说明不同处理的影响，不把默认不插补写成已确认专业决定。"
                + "若以后批准多重插补，须单独记录适用年份。"), List.of(), List.of());
        String text = String.join("\n", merged.executionPrerequisites());
        assertThat(text).contains("不同指标可能采用不同处理", "不得默认统一插补", "若以后批准多重插补", "适用年份")
                .doesNotContain("需确认是否插补、适用指标及方法", "未确认前保持缺失状态并说明不同处理的影响");
        assertThat(text.split("不把默认不插补写成已确认专业决定", -1)).hasSize(2);
    }

    @Test
    void compactsIndividualDefaultProhibitionsOnlyWhenTheSameBoundCompositeAlreadyCoversThem() {
        String question = "失败后最大重试次数与临时文件清理策略是什么？";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("retry", question,
                "最大重试次数和失败后的临时文件处理策略尚未决定，方案不得把默认次数或清理策略写成已确认。")));
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(question
                + "方案不得把默认次数写成已确认。方案不得把默认清理策略写成已确认。"
                + "若重试跨越2026年审批窗口，需要重新授权。方案不得把乙院的清理策略写成已确认。"),
                List.of(), List.of());
        String text = String.join("\n", merged.executionPrerequisites());
        assertThat(text).contains("方案不得把默认次数或清理策略写成已确认", "2026年审批窗口", "乙院的清理策略")
                .doesNotContain("方案不得把默认次数写成已确认", "方案不得把默认清理策略写成已确认");
    }

    @Test
    void compactsAMarkedListWithTheExactPlatformDeliveryBoundaryButKeepsANewHospitalCondition() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("consistency", "一致性指标分母是什么？",
                "一致性指标分母尚未确定。")));
        var prerequisites = new PlanAmbiguityMerger(decisions).merge(List.of(), List.of(), List.of()).executionPrerequisites();
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "交付指标表。\n"
                + "1. 未决事项：一致性指标分母尚未确定。" + UnresolvedDecisionContract.DELIVERY_GUIDANCE));
        ProviderPrerequisiteCompactor.compact(sections, prerequisites, decisions, RAW);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("交付指标表。");
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "交付指标表。\n"
                + "1. 未决事项：一致性指标分母尚未确定。乙院2026年的审批需另外核实。"));
        ProviderPrerequisiteCompactor.compact(sections, prerequisites, decisions, RAW);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("乙院2026年的审批需另外核实");
    }

    @Test
    void directEnhancementDoesNotAppendBareUnknownsAlreadyCoveredByTheSameFullExplanation() {
        String raw = "制定研究方案。A医院2022年的覆盖度尚未确定。是否对缺失病例使用插补尚未决定。"
                + "B医院2022年的覆盖度尚未确定。A医院2026年的覆盖度尚未确定。";
        String coverage = "A医院2022年的覆盖度尚未确定，其他年份不能建立该年的覆盖事实。";
        String imputation = "是否对缺失病例使用插补尚未决定，且不同指标可能采用不同处理，不得默认统一插补。";
        var result = assemble("交付方法与指标表。", raw, List.of(), false, List.of(), List.of(coverage, imputation));
        assertThat(result.ambiguities()).contains(coverage, imputation, "B医院2022年的覆盖度尚未确定。",
                "A医院2026年的覆盖度尚未确定。")
                .doesNotContain("A医院2022年的覆盖度尚未确定。", "是否对缺失病例使用插补尚未决定。");
    }

    @Test
    void recognizesAnExampleInTheBoundQuestionButNeverUpdatesAFutureOrQuotedState() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("completeness",
                "完整性指标（如字段缺失率）的分母应如何定义？",
                "完整性指标分母采用所有有效出院记录，包括字段缺失记录；仅确认完整性分母，一致性指标分母仍未决定。")));
        String source = "完整性指标分母与一致性指标分母尚需分别确定。";
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, decisions.decisions()))
                .contains("完整性指标分母已按本次回答确认", "一致性指标分母尚未确定");
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters("完整性指标的分母尚未确定。", decisions.decisions()))
                .contains("已由用户确认选定");
        for (String preserved : List.of("如果以后确认，完整性指标分母尚未确定。", "引用：“完整性指标分母尚未确定”。",
                "乙院完整性指标分母尚未确定。")) {
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(preserved, decisions.decisions())).isEqualTo(preserved);
        }
    }

    @Test
    void keepsTheRichDenominatorDefinitionReminderWithoutAppendingItsBareMaterialAlias() {
        String finding = "完整性指标的分母口径尚未确定（例如是否包含所有有效记录），会影响缺失率计算。";
        var result = assemble("交付方法与指标表。", RAW, List.of(), false,
                List.of(fact("完整性指标分母尚未确定。"), fact("乙院完整性指标分母尚未确定。")), List.of(finding));
        assertThat(result.ambiguities()).contains(finding, "乙院完整性指标的分母尚未确定。")
                .doesNotContain("完整性指标的分母尚未确定。");
    }

    @Test
    void rejectsCoordinatedPendingParametersWithoutInventingOtherUnknowns() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录数"),
                RAW + "完整性指标分母与一致性指标分母尚需分别确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotApplyCompletenessConfirmationToTheFormatMetric() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        assertThatThrownBy(() -> assemble(table("格式异常指标", "全部有效出院记录数（已确认）"), RAW,
                answers, true, List.of(fact("格式异常指标分母尚需确定。"))))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotInventConfirmationForAnotherMetricEvenWithoutAnExplicitPendingDeclaration() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        assertThatThrownBy(() -> assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n"
                        + "| 格式异常指标 | 全部有效记录数 | 已确认 |", RAW, answers, true, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble("用户已确认格式异常指标分母采用全部有效记录数。", RAW, answers, true, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void acceptsTheSameConfirmedMetricShorthandInAPartiallyConfirmedAnswer() {
        var answers = List.of(new PlanAnswer("completeness", "缺失记录是否纳入完整性指标的分母？",
                "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录；"
                        + "仅确认完整性分母，一致性指标分母仍未决定。"));
        var result = assemble("用户已确认完整性分母包含所有有效出院记录；跨字段一致性指标分母尚未决定。",
                RAW, answers, true, List.of());
        assertThat(result.optimizedPrompt()).contains("完整性分母包含所有有效出院记录");
        assertThatThrownBy(() -> assemble("用户已确认跨字段一致性指标分母包含所有有效出院记录。",
                RAW, answers, true, List.of())).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotInventALoginDecisionForANotificationTaskThatPreservesLoginPermissions() {
        var detector = new AmbiguityDetector();
        assertThat(detector.detect("为订单审批结果设计通知能力。站内信和邮件均已实现。"
                + "不改变订单审批规则和登录权限；只交付方案。"))
                .noneMatch(value -> value.contains("认证与会话方式"));
        assertThat(detector.detect("为用户模块添加登录功能。"))
                .anyMatch(value -> value.contains("认证与会话方式"));
        assertThat(detector.detect("在现有项目中添加登录功能。"))
                .anyMatch(value -> value.contains("认证与会话方式"));
    }

    @Test
    void coversAGroupSummaryOnlyAfterBothHospitalParametersRemainIndependentlyPending() {
        var answers = List.of(new PlanAnswer("a-window", "甲院的观察窗口是什么？", "甲院的比较观察窗口尚未确定。"),
                new PlanAnswer("b-window", "乙院的观察窗口是什么？", "乙院的比较观察窗口尚未确定。"),
                new PlanAnswer("a-threshold", "甲院的异常等待阈值是什么？", "甲院的异常等待阈值尚未确定。"),
                new PlanAnswer("b-threshold", "乙院的异常等待阈值是什么？", "乙院的异常等待阈值尚未确定。"));
        var facts = List.of(fact("两院比较的观察窗口尚未确定。"), fact("异常等待的阈值尚未确定。"),
                fact("两院2026年的观察窗口尚未确定。"));
        String raw = "制定甲院和乙院的候诊时间分析方案，分别保留观察窗口与异常等待阈值。另需核对两院2026年的观察窗口。";
        var result = assemble("交付指标表。", raw, answers, true, facts);
        assertThat(result.ambiguities()).doesNotContain("两院比较的观察窗口尚未确定。", "异常等待的阈值尚未确定。");
        assertThat(result.ambiguities()).contains("两院2026年的观察窗口尚未确定。");
        assertThat(assemble("交付指标表。", raw, answers.subList(0, 1), true, facts).ambiguities())
                .contains("两院比较的观察窗口尚未确定。");
    }

    @Test
    void keepsAnExplicitCurrentParameterChoiceButNotAHypotheticalOne() {
        var result = assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n| 完整性指标 | 全部有效记录数 | 已确认 |",
                RAW + "完整性指标分母采用全部有效记录数。", List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("全部有效记录数");
        assertThatThrownBy(() -> assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n| 完整性指标 | 全部有效记录数 | 已确认 |",
                RAW + "如果以后确认完整性指标分母采用全部有效记录数，再计算。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void updatesOnlyTheActuallyConfirmedParameterAndKeepsOriginalEvidence() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        var result = assemble(table("完整性指标", "全部有效出院记录数") + "\n" + table("格式异常指标", "待确认"),
                RAW, answers, true, List.of(fact("完整性指标分母尚未确定。"), fact("格式异常指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).doesNotContain("完整性指标分母尚未确定");
        assertThat(result.optimizedPrompt()).contains("格式异常指标", "待确认");
        assertThat(result.evidenceCards()).anyMatch(card -> card.evidence().contains("完整性指标分母尚未确定"));
    }

    @Test
    void doesNotTreatUnboundAnswersAsUserConfirmation() {
        var result = assemble("交付方法与指标表。", RAW,
                List.of(new PlanAnswer("forged", "完整性指标分母采用什么口径？", "完整性指标分母采用全部记录。")), false, List.of());
        assertThat(result.optimizedPrompt()).doesNotContain("用户已确认的信息", "完整性指标分母采用全部记录");
    }

    @Test
    void updatesTheOriginalPendingChannelOnlyAfterAnActualSelection() {
        String raw = "我偏好站内信，但本次尚未作出最终渠道选择；不要因为偏好就写成已选定。";
        var chosen = ConfirmedDecisionSet.from(List.of(new PlanAnswer("channel",
                "本次订单审批结果通知采用哪种渠道？", "仅使用站内信通知。")));
        assertThat(ResolvedPlanState.from(chosen, raw).reconcile(raw)).doesNotContain("本次尚未作出最终渠道选择");
        var future = ConfirmedDecisionSet.from(List.of(new PlanAnswer("channel",
                "本次订单审批结果通知采用哪种渠道？", "如果以后确认，仅使用站内信通知。")));
        assertThat(ResolvedPlanState.from(future, raw).reconcile(raw)).contains("本次尚未作出最终渠道选择");
        assertThat(ResolvedPlanState.from(chosen, raw).reconcile("另一院的渠道尚未决定。"))
                .isEqualTo("另一院的渠道尚未决定。");
    }

    @Test
    void doesNotLetAnotherUnknownClauseHideAnAssignedParameter() {
        assertThatThrownBy(() -> assemble("一致性指标分母为全部有效记录数，插补方法尚未决定。",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void validatesParameterHeaderAliasesAndTheNamedPseudoCodeAssignment() {
        assertThatThrownBy(() -> assemble("| 指标 | 分母口径 |\n|---|---|\n| 一致性指标 | 全部有效记录数 |",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble("```text\n一致性指标分母 = 有效记录数\n```",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsARecordScopeDisguisedByAPendingNoteWithoutTheCountSuffix() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录（待确认）"),
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void keepsOtherObjectsAndExplicitConditionalBranches() {
        var result = assemble(table("乙院一致性指标", "乙院已核实的适用记录数")
                        + "\n如果以后确认甲院一致性指标分母为全部有效记录，再按该口径计算。",
                RAW + "甲院一致性指标分母尚未确定。", List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("乙院已核实的适用记录数", "如果以后确认");
    }

    @Test
    void doesNotExposeInternalPlanProtocolInUserFacingText() {
        assertThatThrownBy(() -> assemble("请按 confirmedDecisions 和 planAnswers 执行，保留 questionId。",
                RAW, List.of(), false, List.of())).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void preservesProtocolNamesWhenTheyAreTheExplicitDevelopmentTask() {
        var result = assemble("说明 questionId 字段的 API 契约。", "编写 questionId 字段的 API 说明。",
                List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("questionId");
    }

    @Test
    void keepsOnePendingStatementAcrossTheCopyableBodyAndItsMaterialView() {
        var result = assemble("交付方法与指标表。\n一致性指标分母尚未确定。", RAW,
                List.of(), false, List.of(fact("一致性指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).doesNotContain("本次尚未确定的参数：");
        assertThat(result.sections().stream().filter(s -> s.type() == PromptSectionType.BACKGROUND)
                .map(PromptSection::content).findFirst().orElseThrow()).doesNotContain("一致性指标分母尚未确定");
        assertThat(result.sections().stream().filter(s -> s.type() == PromptSectionType.OUTPUT)
                .map(PromptSection::content).findFirst().orElseThrow()).doesNotContain("一致性指标分母尚未确定");
        assertThat(result.ambiguities()).hasSize(1);
    }

    @Test
    void preservesANewConditionNextToAnAlreadyKnownPendingDeclaration() {
        var result = assemble("交付方法与指标表。\n甲院一致性指标分母尚未确定，2026年的授权需另外核实。", RAW,
                List.of(), false, List.of(fact("甲院一致性指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).contains("2026年的授权需另外核实");
    }

    @Test
    void doesNotTurnAConditionalPremiseIntoAKnownFactInThePlanHint() {
        var guard = ConditionalConfirmationGuard.prepare("若我只确认完整性指标分母包含全部有效记录，不能推断另一指标。",
                List.of("若我只确认完整性指标分母包含全部有效记录，不能推断另一指标。"), List.of());
        assertThatThrownBy(() -> guard.validate("你已说明完整性指标分母包含全部有效记录，因此可沿用。", "questions[0].hint"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void passesTheUploadedUnknownStateToTheProviderBeforeItGeneratesAnySections() {
        var captured = new java.util.ArrayList<com.promptoptimizer.provider.domain.EnhancementProviderRequest>();
        com.promptoptimizer.provider.service.PromptEnhancementProvider provider = request -> {
            captured.add(request);
            return new EnhancementProviderResponse(List.of(
                    new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供病案质量方案。"),
                    new PromptSection(PromptSectionType.TASK, "任务", "制定研究方法。"),
                    new PromptSection(PromptSectionType.OUTPUT, "输出", "交付方法、指标表和必要伪代码。"),
                    new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, List.of());
        };
        var orchestrator = new DefaultEnhancementOrchestrator(
                new com.promptoptimizer.context.service.impl.DefaultContextAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.promptoptimizer.context.service.impl.BinaryContentExtractor(),
                        new com.promptoptimizer.context.service.impl.FileContentSummarizer()),
                new AmbiguityDetector(), new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl(),
                new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl(), provider,
                com.promptoptimizer.identity.support.TestActors.currentActor(), java.time.Clock.systemUTC());
        var result = orchestrator.optimize(new com.promptoptimizer.enhancement.dto.OptimizationRequest(RAW,
                new com.promptoptimizer.context.dto.ContextAnalysisRequest("", List.of(new com.promptoptimizer.context.dto.ContextFileInput(
                        "docs/病案质量方案.md", "完整性指标分母与一致性指标分母尚需分别确定。", "markdown"))),
                new com.promptoptimizer.enhancement.dto.EnhancementOptions(TemplateCode.AUTO, false, true, false),
                List.of(), new com.promptoptimizer.enhancement.dto.PermissionPolicyInput(List.of(), List.of())));
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().template().outputGuidance()).contains("完整性指标的分母：待确认", "跨字段一致性指标的分母：待确认");
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("一致性指标"));
    }

    private static PlanningFactCard fact(String evidence) {
        return new PlanningFactCard("material", PlanningFactCategory.BUSINESS_RULE, "docs/病案质量方案.md", evidence);
    }

    private static String table(String name, String value) {
        return "| 指标 | 分母 |\n|---|---|\n| " + name + " | " + value + " |";
    }

    private static OptimizationResult assemble(String output, String raw, List<PlanAnswer> answers,
                                              boolean confirmed, List<PlanningFactCard> facts) {
        return assemble(output, raw, answers, confirmed, facts, List.of());
    }

    private static OptimizationResult assemble(String output, String raw, List<PlanAnswer> answers,
                                              boolean confirmed, List<PlanningFactCard> facts, List<String> findings) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供病案质量方案。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定病案质量研究方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, findings);
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "方法及指标表", "忠于资料", "示例"),
                List.of(), answers, confirmed, List.of("不得编造数据"), false, 1, raw, facts, List.of());
    }
}
