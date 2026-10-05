package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放实际推荐错误并检查审批状态的对象边界，候选本身及可核验的当前选择始终保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RecommendationApplicabilityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replaysTheActualHospitalRecommendationWithoutMakingEitherUnapprovedRuleAuthoritative() throws Exception {
        Path repository = repositoryRoot();
        Path generation = repository.resolve("docs/testing/evidence/open-expression-2026-10-05/generation/candidate-04");
        var replay = mapper.readTree(generation.resolve("CV-HOSPITAL-M-deepseek_deepseek-v4-pro-plan.json").toFile());
        var cases = mapper.readTree(generation.resolve("cases.json").toFile());
        String raw = "";
        for (var entry : cases) if (entry.path("id").asText().equals("CV-HOSPITAL-M")) raw = entry.path("rawPrompt").asText();
        var digest = mapper.convertValue(replay.path("prepared").path("digest"), PlanningContextDigest.class);
        var question = mapper.convertValue(replay.path("plan").path("questions").get(1), PlanQuestion.class);

        PlanQuestion aligned = PlanRecommendationAligner.align(question, request(raw, digest));

        assertThat(raw).isNotBlank();
        assertThat(question.options()).filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("use_24h");
        assertThat(aligned.options()).noneMatch(PlanOption::recommended);
        assertThat(aligned.options()).extracting(PlanOption::answer).containsExactlyElementsOf(question.options().stream().map(PlanOption::answer).toList());
    }

    @Test
    void doesNotUseAnUnresolvedSourceAsEvidenceEvenWhenItsApprovalGapFollowsTheRuleInAnotherSentence() {
        PlanQuestion question = cancellationQuestion();
        var digest = digest(List.of("预约流程：提前24小时取消不计爽约。该取消免责规则的权威性尚未建立，应由运营负责人核对适用范围。"));
        assertThat(PlanRecommendationAligner.align(question, request("为门诊预约整理取消免责边界方案。", digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void doesNotTurnTheExplicitComparisonOnlyInstructionIntoARecommendation() {
        var digest = digest(List.of("旧流程说明提前24小时取消不计爽约。"));
        var raw = "取消免责存在差异：旧流程提前24小时，新草稿提前12小时。仅展示两种口径，不擅自采用其中一项。";
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request(raw, digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void keepsAnExplicitCurrentChoiceInsteadOfTreatingTheRejectedDraftAsAWholeQuestionVeto() {
        var digest = digest(List.of("旧流程说明提前24小时取消不计爽约。新草稿建议提前12小时，尚未批准。"));
        var raw = "本次明确采用提前24小时取消不计爽约。12小时新草稿未批准，本次不采用。";
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request(raw, digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("legacy");
    }

    @Test
    void keepsAnApprovedCurrentRuleWithItsCompleteCondition() {
        var digest = digest(List.of("运营负责人已批准本季度适用的取消规则：提前24小时取消不计爽约。"));
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request("整理本季度取消免责分析方案。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("legacy");
    }

    @Test
    void doesNotTransferAnUnapprovedRefundDecisionToARecognizedCurrentFramework() {
        var digest = new PlanningContextDigest("", List.of("Vue 3"), List.of(), List.of(),
                List.of("退款审批规则的权威性尚未建立，尚未批准。"), "COMPLETE", 2, List.of());
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("修复 Vue 3 页面，退款审批标准尚未决定。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("vue");
    }

    @Test
    void doesNotRecommendANegatedApprovalStatement() {
        var digest = digest(List.of("提前24小时取消不计爽约并非已批准规则，不代表本季度适用。"));
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request("讨论取消免责口径。", digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void doesNotTreatAnUnapprovedFutureFrameworkAsCurrentEvenWithLongExplanatoryText() {
        var digest = digest(List.of("Vue 3 迁移方案能够组织页面组件并改善开发体验，但这仅是候选方案，审批状态仍未确定，本次不能视为已采用。"));
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("为页面迁移比较技术方案。", digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void doesNotUseATestFactCardToRecommendAProductionFramework() {
        var card = new PlanningFactCard("F1", PlanningFactCategory.ANALYSIS_TOOL, PlanningFactOrigin.TEST_SOURCE,
                "src/test/FrameworkFixture.java", "React");
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 1, List.of(), List.of(card));
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("为正式页面选择实现框架。", digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void keepsCurrentProductionFactsAndAnExplicitMigrationTarget() {
        var card = new PlanningFactCard("F1", PlanningFactCategory.ANALYSIS_TOOL, PlanningFactOrigin.PROJECT_SOURCE,
                "apps/web/package.json", "当前页面使用 React");
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 1, List.of(), List.of(card));
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("修复现有页面。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("react");
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("本次迁移到 Vue 3，React 是迁出来源。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("vue");
    }

    @Test
    void keepsAnApprovedCurrentRuleWhenTheOtherDraftIsExplicitlyNotApproved() {
        var digest = digest(List.of("两份材料的审批状态均已明确。当前已批准规则为提前24小时取消不计爽约。新草稿建议提前12小时，尚未批准。"));
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request("按已批准的当前制度整理取消边界。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("legacy");
    }

    @Test
    void doesNotAttachTheFollowingRefundApprovalGapToCancellationEvidence() {
        var digest = digest(List.of("提前24小时取消不计爽约。该退款规则的审批状态尚未明确。"));
        assertThat(PlanRecommendationAligner.align(cancellationQuestion(), request("整理门诊取消免责口径。", digest)).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("legacy");
    }

    @Test
    void doesNotUseAnExplicitlyTaggedTestSummaryToRecommendABusinessFramework() {
        var digest = digest(List.of("[TEST_SOURCE] src/test/Fixture.java：React"));
        assertThat(PlanRecommendationAligner.align(frameworkQuestion(), request("为正式页面选择实现框架。", digest)).options())
                .noneMatch(PlanOption::recommended);
    }

    private static PlanningProviderRequest request(String raw, PlanningContextDigest digest) {
        return new PlanningProviderRequest(raw, "", List.of(), digest);
    }

    private static PlanningContextDigest digest(List<String> summaries) {
        return new PlanningContextDigest("", List.of(), List.of(), List.of(), summaries, "COMPLETE", summaries.size(), List.of());
    }

    private static PlanQuestion cancellationQuestion() {
        return new PlanQuestion("cancel", "取消免责边界应如何定义？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("legacy", "采用旧流程24小时", "以旧流程说明为准，提前24小时取消不计爽约。",
                                "采用旧流程说明：提前24小时取消不计爽约。", true),
                        new PlanOption("draft", "采用新草稿12小时", "以新会议草稿建议为准，提前12小时取消不计爽约。",
                                "采用新会议草稿建议：提前12小时取消不计爽约。", false)), List.of(), true);
    }

    private static PlanQuestion frameworkQuestion() {
        return new PlanQuestion("framework", "页面采用哪个框架？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("react", "React", "开发页面", "使用 React 开发页面。", true),
                        new PlanOption("vue", "Vue 3", "开发页面", "使用 Vue 3 开发页面。", false)), List.of(), true);
    }

    /** 直接回放仓库中冻结的实际响应；从模块或仓库根目录运行测试均可定位同一份证据。 */
    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("AGENTS.md"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("无法定位仓库中的真实推荐回放证据");
        return current;
    }
}
