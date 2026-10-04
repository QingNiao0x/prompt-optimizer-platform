package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.AmbiguityReference;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实模型的重复提醒，验证最终 API 与段落，而非仅验证字符串工具。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanAmbiguityMergeTest {
    private static final String CONFLICT = "资料对“审批阈值”存在不同取值：src/rules/审批规则.txt（三万元）与 docs/新审批方案.txt（五万元）。请确认本次采用哪一项。";
    private static final String REGION_CONFLICT = "资料对“研究范围”存在不同取值：研究资料.txt（广东省）与 新方案.txt（浙江省）。请确认本次采用哪一项。";

    @Test
    void shouldMergeOnlyTheReminderForTheSameBusinessObject() {
        var answers = List.of(new PlanAnswer("approval", "审批规则采用什么标准？", "暂不确定"),
                new PlanAnswer("refund", "退款规则采用什么标准？", "暂不确定"));
        var result = assemble(List.of("审批标准尚未明确。", "退款标准尚未明确。", "紧急订单的审批标准尚未明确。"),
                List.of(), answers);
        assertThat(result.ambiguities()).hasSize(3)
                .contains("紧急订单的审批标准尚未明确。");
        assertSynchronized(result);
    }

    @Test
    void shouldNotUseAnApprovalAnswerToResolveRefundRules() {
        var decisions = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("approval", "审批规则采用什么标准？", "超过五万元由财务审批")));
        assertThat(decisions.coversUnknown("退款规则尚未明确。")).isFalse();
        assertThat(decisions.coversUnknown("紧急订单审批规则尚未明确。")).isFalse();
    }

    @Test
    void shouldRemoveAbsenceDeclarationsButRetainSpecificProblemsFollowingNoIssues() {
        var result = assemble(List.of("无。本题必要的事实、范围、读者和交付形式均已提供，不存在影响任务目标且目前缺失的业务决定。",
                "无已知冲突，但退款期限尚未确定。"), List.of(), List.of());
        assertThat(result.ambiguities()).containsExactly("无已知冲突，但退款期限尚未确定。");
    }

    @Test
    void shouldMergeResearchDecisionLabelsWithBoundQuestionsWithoutDroppingNewPopulation() {
        var answers = List.of(new PlanAnswer("rounds", "交互式计划确认组最多允许几轮提问？", "暂不确定"),
                new PlanAnswer("agreement", "两名盲评者之间的一致性应如何评价？", "暂不确定，不提前指定统计检验；按评分变量类型提出候选，并说明选择条件。"));
        var result = assemble(List.of("以下事项尚未决定，须保留为预注册前待确认条件，不得默认补全：",
                "交互式计划确认组的最大提问轮次。",
                "两名盲评者之间一致性的评价方式（按评分变量类型提出候选并说明选择条件，不提前指定统计检验）。",
                "三名盲评者之间一致性的评价方式。"), List.of(), answers);
        assertThat(result.ambiguities()).hasSize(3).contains("三名盲评者之间一致性的评价方式。");
    }

    @Test
    void shouldCopyOneAuthoritativeReminderWithoutRemovingNewProviderConditions() {
        var result = new OptimizationResultAssembler().assemble(new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "审批流程。"),
                new PromptSection(PromptSectionType.TASK, "任务", "整理审批方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "审批说明。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "- 审批标准尚未明确。\n- 紧急订单审批标准尚未明确。")),
                "test", "test", false, List.of("审批标准尚未明确。", "紧急订单审批标准尚未明确。")),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "输出", "结果可核对", "示例"), List.of(),
                List.of(new PlanAnswer("approval", "审批标准是什么？", "暂不确定")), true,
                List.of("不得削弱现有功能"), false, 1);
        assertThat(result.optimizedPrompt()).doesNotContain("- 审批标准尚未明确。")
                .contains("该问题尚未确定：审批标准是什么？");
        assertThat(result.optimizedPrompt().split("紧急订单审批标准尚未明确", -1)).hasSize(2);
        assertThat(result.ambiguities()).hasSize(2);
    }

    @Test
    void shouldGroupOneConflictRegisteredByBothServerAndUnresolvedAnswer() {
        String explanation = "本次订单审批采用哪个审批阈值：src/rules/审批规则.txt 为三万元，docs/新审批方案.txt 为五万元。确认前不能确定修改位置。";
        var result = assemble(List.of(explanation), List.of(CONFLICT),
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, "暂不确定")));
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.optimizedPrompt()).contains(CONFLICT, "确认前不能确定修改位置");
    }

    @Test
    void shouldMergeUnresolvedExplanationWithoutLosingNewConditions() {
        String explanation = "参考寿命表来源未确定（confirmedDecisions 中 lifetable_source 为“暂不确定”）。缺少寿命表无法计算YLL；需要明确使用哪一版寿命表、按年龄取值口径及是否分性别。";
        var result = assemble(List.of(explanation), List.of(),
                List.of(new PlanAnswer("lifetable_source", "参考寿命表应如何确定？", "暂不确定")));
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.optimizedPrompt()).contains("参考寿命表应如何确定", "哪一版寿命表", "是否分性别")
                .doesNotContain("confirmedDecisions", "lifetable_source");
        assertSynchronized(result);
    }

    @Test
    void shouldMergeUnlistedQuestionExplanationButKeepSeparateBusinessChoices() {
        String question = "附表部分希望用什么格式呈现？";
        String explanation = "附表部分希望用什么格式呈现尚未确定（用户回答暂不确定）。附表形式会影响呈现要求，确认前不擅自指定格式。";
        String independent = "附表数据是否允许对外公开？";
        var result = assemble(List.of(explanation, independent), List.of(),
                List.of(new PlanAnswer("table_format", question, "暂不确定")));
        assertThat(result.ambiguities()).hasSize(2).contains(independent);
        assertThat(result.optimizedPrompt()).contains("确认前不擅自指定格式");
    }

    @Test
    void shouldNeverMergeAdditionalConditionsIntoAnAlreadyResolvedQuestion() {
        String fresh = "参考寿命表来源未确定；是否需要采用2026版的女性寿命表？";
        var result = assemble(List.of(fresh), List.of(),
                List.of(new PlanAnswer("life", "参考寿命表应如何确定？", "采用2020版全人群表")));
        assertThat(result.ambiguities()).containsExactly(fresh);
    }

    @Test
    void shouldReplayRealUndecidedConflictAsOneIssue() {
        var result = assemble(List.of("审批阈值冲突：src/rules/审批规则.txt 规定三万元，docs/新审批方案.txt 规定五万元，用户尚未确认采用哪一项。该值直接影响审批触发条件和财务复核范围，必须明确。"),
                List.of(CONFLICT), List.of(new PlanAnswer("context-conflict-1", CONFLICT, "暂不确定")));
        assertThat(result.ambiguities()).containsExactly(CONFLICT);
        assertSynchronized(result);
    }

    @Test
    void shouldReplayFiveUnresolvedQuestionsAndOneNewConflictWithoutDuplicates() {
        var result = assemble(List.of(
                "数据来源未确认：请提供心脑血管疾病死亡率数据的来源（如统计年鉴、卫生部门报告等）。",
                "死亡率定义未确认：请明确死亡率的具体定义（如粗死亡率、年龄标化死亡率等）。",
                "时间范围未确认：请指定需要分析的时间段（如年份区间）。",
                "病种范围未确认：请明确心脑血管疾病具体包括哪些病种（如冠心病、脑卒中等）。",
                "分析目的未确认：请说明分析的主要目的（如描述趋势、比较地区差异、识别风险因素等）。"),
                List.of(REGION_CONFLICT), researchAnswers());
        assertThat(result.ambiguities()).containsExactly(REGION_CONFLICT,
                "该问题尚未确定：心脑血管疾病死亡率数据从哪里获取？",
                "该问题尚未确定：死亡率的具体定义是什么？",
                "该问题尚未确定：需要分析哪个时间段的数据？",
                "该问题尚未确定：心脑血管疾病具体包括哪些病种？",
                "该问题尚未确定：分析的主要目的是什么？");
        assertSynchronized(result);
    }

    @Test
    void shouldKeepNewValuesScopeAndToolVersion() {
        List<String> extra = List.of("审批阈值新增八万元，是否适用于退款订单？",
                "资料对“审批阈值”存在不同取值：src/rules/审批规则.txt（三万元）与 docs/新审批方案.txt（五万元）。另外退款订单需要单独授权。",
                "数据来源是否允许包含未成年人病历？", "使用 R 的具体版本尚未确定。",
                "死亡率采用粗死亡率还是年龄标化死亡率？");
        var result = assemble(extra, List.of(CONFLICT), List.of(
                new PlanAnswer("context-conflict-1", CONFLICT, "暂不确定"),
                new PlanAnswer("data-source", "数据从哪里获取？", "暂不确定"),
                new PlanAnswer("analysis-tool", "使用哪种分析工具？", "R")));
        assertThat(result.ambiguities()).containsAll(extra).contains(CONFLICT);
    }

    @Test
    void shouldKeepNewConflictAfterAnExplicitAnswerAndRemoveOnlyTheOldPair() {
        String next = "资料对“审批阈值”存在不同取值：docs/新审批方案.txt（五万元）与 docs/补充方案.txt（八万元）。请确认本次采用哪一项。";
        var result = assemble(List.of(CONFLICT, next), List.of(next),
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, "本次以新审批方案中的五万元阈值为准。")));
        assertThat(result.ambiguities()).containsExactly(next);
    }

    @Test
    void shouldNotEraseOperatorsOrMergeSeparateSubjects() {
        var findings = List.of("审批条件采用金额>50000？", "审批条件采用金额>=50000？",
                "数据来源是否允许包含未成年人病历？", "数据来源未确认（例如是否允许跨院共享）？");
        assertThat(assemble(findings, List.of(), List.of(
                new PlanAnswer("data-source", "数据来源是什么？", "暂不确定"))).ambiguities()).containsAll(findings);
    }

    @Test
    void shouldDeduplicateBeforeApplyingLimitAndReportOverflow() {
        List<PlanAnswer> answers = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> new PlanAnswer("question-" + i, "第" + i + "项独立业务选择？", "暂不确定")).toList();
        var result = assemble(List.of("需要确认新增退款授权？"), List.of(REGION_CONFLICT, CONFLICT), answers);
        assertThat(result.ambiguities()).hasSize(8).startsWith(REGION_CONFLICT, CONFLICT);
        assertThat(result.warnings()).anyMatch(w -> w.contains("另有 3 项未展示"));
        assertSynchronized(result);
    }

    @Test
    void shouldKeepNewConditionsEvenWhenProviderReferencesAnExistingQuestion() {
        String finding = "数据来源是否允许包含未成年人病历？";
        var result = assemble(List.of(finding), List.of(), researchAnswers(),
                List.of(new AmbiguityReference(finding, "data-source")));
        assertThat(result.ambiguities()).contains(finding);
        var invalidReference = assemble(List.of(finding), List.of(), researchAnswers(),
                List.of(new AmbiguityReference(finding, "unbound-id")));
        assertThat(invalidReference.ambiguities()).contains(finding);
    }

    @Test
    void shouldUseBoundIdOnlyWhenBothQuestionsCoverTheSameGenericReminder() {
        var answers = List.of(new PlanAnswer("source-a", "A 项目的数据来源是什么？", "暂不确定"),
                new PlanAnswer("source-b", "B 项目的数据来源是什么？", "暂不确定"));
        String finding = "数据来源未确认。";
        assertThat(assemble(List.of(finding), List.of(), answers).ambiguities()).hasSize(3);
        assertThat(assemble(List.of(finding), List.of(), answers,
                List.of(new AmbiguityReference(finding, "source-a"))).ambiguities()).hasSize(2);
        assertThat(assemble(List.of(finding), List.of(), answers,
                List.of(new AmbiguityReference(finding, "forged-id"))).ambiguities()).hasSize(3);
    }

    @Test
    void shouldIgnoreMalformedReferencesAndKeepNewQuestions() {
        String finding = "数据来源是否允许包含未成年人病历？";
        var result = assemble(List.of(finding), List.of(), researchAnswers(),
                java.util.Arrays.asList(null, new AmbiguityReference("未在提醒数组中出现", "data-source"),
                        new AmbiguityReference(finding, "../invalid")));
        assertThat(result.ambiguities()).contains(finding);
        assertSynchronized(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"输出格式需要确认；是否需要提供数据？", "输出格式需要确认，是否需要提供数据？",
            "输出格式需要确认,是否需要提供数据？", "输出格式需要确认。是否需要提供数据？"})
    void shouldNotEraseASecondQuestionAfterARepeatedReminder(String finding) {
        var answers = List.of(new PlanAnswer("output-format", "输出格式是什么？", "Excel"));
        assertThat(assemble(List.of(finding), List.of(), answers).ambiguities()).contains(finding);
        assertThat(assemble(List.of(finding), List.of(), answers,
                List.of(new AmbiguityReference(finding, "output-format"))).ambiguities()).contains(finding);
    }

    @Test
    void shouldKeepLegacyAuthenticationDeduplicationWithoutSuppressingSessionQuestions() {
        var answers = List.of(new PlanAnswer("auth-mode", "当前采用哪种认证方式？", "用户名密码"));
        String newQuestion = "当前认证方式的会话有效期需要多长？";
        assertThat(assemble(List.of("当前认证方式未明确。", newQuestion), List.of(), answers).ambiguities())
                .containsExactly(newQuestion);
    }

    @Test
    void shouldNotUseConflictingReferencesToGuessWhichQuestionWasAnswered() {
        var answers = List.of(new PlanAnswer("source-a", "A 项目的数据来源是什么？", "暂不确定"),
                new PlanAnswer("source-b", "B 项目的数据来源是什么？", "暂不确定"));
        String finding = "数据来源未确认。";
        var result = assemble(List.of(finding), List.of(), answers, List.of(
                new AmbiguityReference(finding, "source-a"), new AmbiguityReference(finding, "source-b")));
        assertThat(result.ambiguities()).hasSize(3).contains(finding);
    }

    @Test
    void shouldKeepGenericReferencedCompoundQuestionsAndAdditionalClauses() {
        var answers = List.of(new PlanAnswer("chart-colors", "图表配色是什么？", "蓝色"));
        String finding = "图表配色需要确认；是否需要提供数据？";
        assertThat(assemble(List.of(finding), List.of(), answers,
                List.of(new AmbiguityReference(finding, "chart-colors"))).ambiguities()).containsExactly(finding);
    }

    @Test
    void shouldResolveAnUnlistedDimensionThroughValidatedReferenceWithoutLosingNewConditions() {
        var answers = List.of(new PlanAnswer("chart-colors", "图表配色是什么？", "暂不确定"));
        var findings = List.of("图表配色尚未确定。", "图表配色是否需要兼顾色盲访问？");
        var result = assemble(findings, List.of(), answers, List.of(
                new AmbiguityReference(findings.get(0), "chart-colors"),
                new AmbiguityReference(findings.get(1), "chart-colors")));
        assertThat(result.ambiguities()).containsExactly("该问题尚未确定：图表配色是什么？", findings.get(1));
    }

    private List<PlanAnswer> researchAnswers() {
        return List.of(new PlanAnswer("region", "研究覆盖哪个地区？", "广东省"),
                new PlanAnswer("data-source", "心脑血管疾病死亡率数据从哪里获取？", "暂不确定"),
                new PlanAnswer("mortality-definition", "死亡率的具体定义是什么？", "暂不确定"),
                new PlanAnswer("time-period", "需要分析哪个时间段的数据？", "暂不确定"),
                new PlanAnswer("disease-scope", "心脑血管疾病具体包括哪些病种？", "暂不确定"),
                new PlanAnswer("analysis-purpose", "分析的主要目的是什么？", "暂不确定"));
    }

    private OptimizationResult assemble(List<String> findings, List<String> server, List<PlanAnswer> answers) {
        return assemble(findings, server, answers, List.of());
    }

    private OptimizationResult assemble(List<String> findings, List<String> server, List<PlanAnswer> answers,
                                       List<AmbiguityReference> references) {
        return new OptimizationResultAssembler().assemble(new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供的资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "按确认结果开展工作。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "说明过程和结果。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造事实。")), "test", "test", false, findings, references),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可验证", "示例"),
                server, answers, true, List.of("不得削弱现有功能"), false, 1);
    }

    private void assertSynchronized(OptimizationResult result) {
        assertThat(result.sections()).filteredOn(s -> s.type() == PromptSectionType.CLARIFICATIONS)
                .singleElement().satisfies(s -> assertThat(s.content()).isEqualTo(
                        String.join("\n", result.ambiguities().stream().map(a -> "- " + a).toList())));
    }
}
