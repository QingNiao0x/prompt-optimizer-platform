package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 从生产结果组装入口校验业务规则与复制正文，不用界面旁路字段代替交付契约。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExecutionContractAssemblyTest {
    @Test
    void shouldKeepBehaviorChoicesSeparateFromCurrentRegionFacts() {
        var answers = List.of(
                new PlanAnswer("trigger", "“当前地区有记录时提示用户是否自动填充”在什么时机触发？", "先查详情，再确认填充。"),
                new PlanAnswer("empty", "当前地区没有记录时，是否还需要给出提示？", "不弹窗，沿用无匹配处理。"),
                new PlanAnswer("stack", "当前项目使用什么框架？", "Vue 3"));
        var decisions = ConfirmedDecisionSet.from(answers).decisions();
        assertThat(decisions.get(0).topic()).isEqualTo("触发时机");
        assertThat(decisions.get(1).topic()).isEqualTo("无匹配提示");
        assertThat(decisions.subList(0, 2)).allMatch(decision ->
                decision.scope() == com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.CHOICE);
        assertThat(decisions.get(2).scope()).isEqualTo(
                com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.CURRENT_STATE);
    }

    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void shouldKeepExplainedUnknownAnswersPendingAndCopyTheirBoundaries() {
        String answer = "暂不确定。参考寿命表仍未提供，不能将任何具体标准表作为已确认选择；仅预留读取和校验接口，确认前不得计算相应 YLL。";
        var result = assemble("设计 YLL 分析框架。", "预留寿命表接口。", List.of(),
                List.of(new PlanAnswer("life", "采用哪份参考寿命表？", answer)), List.of());
        assertThat(result.ambiguities()).singleElement().asString().contains("参考寿命表", "尚未确定");
        // 裸未知状态统一由“该问题尚未确定”表达；完整业务边界仍须进入复制正文。
        assertThat(result.optimizedPrompt()).contains(answer.substring(answer.indexOf('。') + 1),
                "该问题尚未确定", "执行前须确认");
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.TASK)
                .singleElement().satisfies(section -> assertThat(section.content()).doesNotContain("用户已确认的信息"));
    }

    @Test
    void shouldRetainConfirmedPartOfAMixedAnswerWithoutResolvingItsMissingVersion() {
        String answer = "使用 R，但版本暂不确定。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("tool", "分析工具及版本是什么？", answer)));
        assertThat(decisions.decisions().getFirst().scope())
                .isEqualTo(com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.UNRESOLVED);
        assertThat(decisions.retrievalQuery("分析资料")).contains("使用 R").doesNotContain("版本暂不确定");
        var result = assemble("分析资料", "整理框架。", List.of(),
                List.of(new PlanAnswer("tool", "分析工具及版本是什么？", answer)), List.of());
        assertThat(result.optimizedPrompt()).contains("使用 R", "版本暂不确定", "执行前须确认");
    }

    @Test
    void shouldNotTreatHistoricalOrConditionalUncertaintyAsAnUnresolvedAnswer() {
        for (String answer : List.of("之前不确定，现在明确选择 R。", "采用 R；如果版本未确定则先检查项目锁文件。",
                "计算不确定性区间。", "没有尚未确定的业务选择。", "用户未确认前不得填充。", "如果版本未确定，先查看锁文件。")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("tool", "分析工具是什么？", answer)));
            assertThat(decisions.decisions().getFirst().scope())
                    .isNotEqualTo(com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.UNRESOLVED);
        }
    }

    @Test
    void shouldPreserveImplementationChecksInTaskInsteadOfAsUserBlockers() {
        String check = "登录态地区取值实现以现有代码核查结果为准，不新增或猜测接口参数。";
        String known = "地区范围过滤由服务端负责，前端不承担该职责。";
        String actualDecision = "退款订单是否也适用地区限制尚未确定，需要用户确认。";
        var result = assemble("服务端负责地区范围过滤。完善登录态地区匹配。", "核查现有实现。",
                List.of(known, check, actualDecision), List.of(), List.of());
        assertThat(result.ambiguities()).containsExactly(actualDecision);
        assertThat(result.optimizedPrompt()).contains(known, check, actualDecision);
    }

    @Test
    void shouldNotDismissUnprovenFactsOrMixedConflictsAsImplementationChecks() {
        for (String finding : List.of("地区范围过滤由前端负责。", "登录态地区取值实现以现有代码核查结果为准，但两份资料存在冲突，需要用户确认。",
                "登录态地区取值实现尚未提供，是否需要新增地区权限接口？")) {
            var result = assemble("服务端负责地区范围过滤。", "核查实现。", List.of(finding), List.of(), List.of());
            assertThat(result.ambiguities()).containsExactly(finding);
        }
    }

    @Test
    void shouldTreatConfirmationUiAsAChoiceInsteadOfCurrentRegion() {
        var decision = ConfirmedDecisionSet.from(List.of(new PlanAnswer("autofill_confirm_ui",
                "“当前地区有时提示用户是否自动填充基本信息”采用哪种确认方式？",
                "采用匹配成功后弹窗确认，用户确认后填充。"))).decisions().getFirst();
        assertThat(decision.scope()).isEqualTo(com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.CHOICE);
        assertThat(decision.topic()).isEqualTo("确认方式");
    }

    @Test
    void shouldMergeDirectConflictExplanationsWithoutInventingAPlanAnswer() {
        String conflict = "资料对“审批金额阈值”存在不同取值：docs/旧.md（30000元）与 docs/方案.md（50000元）。请确认本次采用哪一项。";
        String repeated = "本次订单审批采用哪个审批金额阈值：docs/旧.md 为 30000 元，docs/方案.md 为 50000 元。未确认前无法定稿。";
        var result = assemble("审批阈值冲突，请先确认。", "整理方案。", List.of(repeated), List.of(), List.of(conflict));
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.optimizedPrompt()).contains("30000元", "50000元", "未确认前无法定稿");
    }

    @Test
    void shouldReplaceTheResolvedRawChoiceWithoutHidingTheNewThreshold() {
        String old = "三万元和五万元两个审批阈值在附件中冲突，需要先确认采用哪一个。";
        String question = "资料对“审批金额阈值”存在不同取值：docs/旧.md（30000元）与 docs/方案.md（50000元）。请确认本次采用哪一项。";
        String fresh = "资料对“审批金额阈值”存在不同取值：docs/方案.md（50000元）与 docs/新增.md（80000元）。请确认本次采用哪一项。";
        var result = assemble(old + "必须保持其他审批行为兼容。", "采用已确认的50000元阈值。",
                List.of(), List.of(new PlanAnswer("context-conflict-1", question,
                        "本次采用50000元阈值；严格大于50000元才复核，等于50000元不复核。")), List.of(fresh));
        assertThat(result.optimizedPrompt()).doesNotContain(old)
                .contains("等于50000元不复核", "必须保持其他审批行为兼容", fresh);
        assertThat(result.ambiguities()).containsExactly(fresh);
        var unresolved = assemble(old, "整理方案。", List.of(),
                List.of(new PlanAnswer("context-conflict-1", question, "暂不确定")), List.of());
        assertThat(unresolved.optimizedPrompt()).contains(old, "执行前须确认");
    }

    @Test
    void shouldKeepTranslationOutputExclusiveInBothModes() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var constraints = new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl().complete(context,
                new com.promptoptimizer.enhancement.dto.PermissionPolicyInput(List.of(), List.of()), true, TemplateCode.GENERAL);
        for (boolean planned : List.of(false, true)) {
            var result = assembler.assemble(new EnhancementProviderResponse(List.of(
                    new PromptSection(PromptSectionType.BACKGROUND, "背景", "翻译问候语。"),
                    new PromptSection(PromptSectionType.TASK, "任务", "翻译Good morning, everyone.，只输出译文。"),
                    new PromptSection(PromptSectionType.OUTPUT, "输出", "仅一行简体中文译文，不附解释。"),
                    new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留问候语气。")), "test", "test", false),
                    context, new PromptTemplate(TemplateCode.GENERAL, "译文", "遵守原文", "示例"),
                    List.of(), List.of(), planned, constraints, false, 1, "翻译Good morning, everyone.，只输出译文。");
            assertThat(result.optimizedPrompt()).doesNotContain("并说明关键依据、适用范围和限制条件")
                    .contains("仅在任务需要且未限制额外说明时", "不得在代码、日志或响应中泄露", "以下操作必须先获得人工确认");
            assertThat(result.ambiguities()).isEmpty();
        }
    }

    @Test
    void shouldPreserveExplicitRulesEvenWhenTheDraftOmitsThem() {
        var result = assemble("完善基线匹配。取消时保持原值；只填 null 或空字符串，保留 0 和 false。",
                "完善匹配与填充。", List.of(), List.of(), List.of());

        assertThat(result.optimizedPrompt()).contains("取消时保持原值", "只填 null 或空字符串", "保留 0 和 false");
        assertThat(result.appliedConstraints()).contains("不得泄露凭据");
    }

    @Test
    void shouldRejectReversedCancellationEvenIfTheCorrectRuleAppearsElsewhere() {
        assertThatThrownBy(() -> assemble("取消时保持原值。", "取消原值：移除保持原值的逻辑选项。",
                List.of(), List.of(), List.of())).isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldRejectClearingValuesInsteadOfFillingEmptyFields() {
        assertThatThrownBy(() -> assemble("经用户确认只填 null 或空字符串，保留 0 和 false。",
                "空值处理：若需清空某字段，仅允许填入 null 或空字符串。", List.of(), List.of(), List.of()))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldRejectAnOppositeRuleInAResearchTask() {
        assertThatThrownBy(() -> assemble("撰写研究报告。不得编造参考文献。",
                "撰写研究报告。可以编造参考文献。", List.of(), List.of(), List.of()))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldRejectAChangedBoundInAnAdministrativeReport() {
        assertThatThrownBy(() -> assemble("撰写工作总结。报告不超过1000字。",
                "撰写工作总结。报告不超过2000字。", List.of(), List.of(), List.of()))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldKeepRealExecutionPrerequisitesInTheCopiedConstraints() {
        String question = "计算 YLL 使用哪份参考寿命表？该选择会改变计算结果。";
        for (boolean planned : List.of(false, true)) {
            var result = assemble("根据研究资料计算 YLL。", "整理分析流程。",
                    planned ? List.of() : List.of(question),
                    planned ? List.of(new PlanAnswer("life-table", question, "暂不确定")) : List.of(), List.of());

            assertThat(result.optimizedPrompt()).as("planned=%s", planned).contains("参考寿命表", "执行前须确认");
            assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.CONSTRAINTS)
                    .singleElement().satisfies(section -> assertThat(section.content()).contains("参考寿命表"));
        }
    }

    @Test
    void shouldNotCopyResolvedQuestionsOrGenericPlaceholders() {
        var result = assemble("分析广东省年度资料。", "整理分析方案。",
                List.of("研究地区尚未明确。", "尚未明确输入来源、参数格式或调用方式。"),
                List.of(new PlanAnswer("region", "研究地区具体是哪里？", "广东省")), List.of());

        assertThat(result.optimizedPrompt()).contains("广东省")
                .doesNotContain("研究地区尚未明确", "尚未明确输入来源", "执行前须确认");
    }

    @Test
    void shouldCarryNewConflictAndKeepDisplayTruncationOutOfTheExecutionContract() {
        List<String> serverFindings = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "资料对“统计字段" + index + "”存在不同取值：旧口径与新口径。请确认本次采用哪一项。")
                .toList();
        var result = assemble("按资料设计统计报告。", "整理统计方案。", List.of(), List.of(), serverFindings);

        assertThat(result.ambiguities()).hasSize(8);
        for (String finding : serverFindings) assertThat(result.optimizedPrompt()).contains(finding);
        assertThat(result.warnings()).anyMatch(message -> message.contains("展示前 8 项"));
    }

    @Test
    void shouldLeaveClearDirectEnhancementFreeOfExtraQuestions() {
        var result = assemble("将 Hello 翻译为中文，只输出译文。", "将 Hello 翻译为中文，只输出译文。",
                List.of(), List.of(), List.of());
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.optimizedPrompt()).doesNotContain("执行前须确认");
    }

    @Test
    void shouldNotTreatRiskRankingAsAProgrammingSortGap() {
        assertThat(new AmbiguityDetector().detect("为普通租房者整理合同风险，按高、中、低风险等级排序。"))
                .isEmpty();
        // 只出现业务名词不能掩盖真实算法缺口，缺少比较规则的开发任务仍需澄清。
        assertThat(new AmbiguityDetector().detect("为业务记录实现风险排序算法。"))
                .anyMatch(finding -> finding.contains("顺序"));
    }

    private OptimizationResult assemble(String rawPrompt, String task, List<String> findings,
                                        List<PlanAnswer> answers, List<String> serverFindings) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "本次用户提供的任务资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付用户要求的结果。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留明确要求，不编造事实。")
        ), "test", "test-model", false, findings);
        return assembler.assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "交付结果", "符合已确认的交付要求", "示例"),
                serverFindings, answers, !answers.isEmpty(), List.of("不得泄露凭据"), false, 1, rawPrompt);
    }
}
