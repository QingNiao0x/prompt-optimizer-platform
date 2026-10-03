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
    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

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
