package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定真实模型验收中的资料污染、弱推荐和自定义冲突确认反例；不调用外部模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class BusinessAcceptanceRegressionTest {
    private static final String CONFLICT = "资料对“审批阈值”存在不同取值：docs/旧规则.txt（三万元）与 docs/新方案.txt（五万元）。请确认本次采用哪一项。";

    @Test
    void shouldNotPromoteInternalInstructionsOrUnrelatedResearchToAnalyticsFacts() {
        var context = snapshot(List.of(
                file("src/main/java/provider/PromptProvider.java", "String prompt = \"\"\"\nFREE_TEXT 必须为 true；仅返回一个 JSON 对象。\n\"\"\";"),
                file("src/main/java/enhancement/FactExtractor.java", "Pattern rule = Pattern.compile(\"(必须|不得|不超过)\");"),
                file("docs/research/Qoder调研.md", "# Qoder 调研\n统计分析单次处理不超过10万行。"),
                file("docs/research/Qoder提示词优化资料核对.md#chunk-1", "# 外部产品资料\n统计分析单次处理不超过10万行。"),
                file("docs/统计日志规则.md", "统计日志必须按上海时区聚合。"),
                file("src/test/java/AnalyticsTest.java", "统计日志必须按 UTC 聚合。")
        ));
        var extraction = new PlanningFactCardExtractor().extract(context,
                "审计统计日志模块并补充统计设计，不得把测试样例和调研内容当作业务事实。");
        assertThat(extraction.cards()).singleElement().satisfies(card -> {
            assertThat(card.sourcePath()).isEqualTo("docs/统计日志规则.md");
            assertThat(card.evidence()).contains("上海时区");
        });
        var research = new PlanningEvidencePolicy("研究心脑血管疾病并进行 YLL 分析");
        assertThat(research.allows(file("research/研究方案.md#chunk-1", "研究地区：广东省"))).isTrue();
        assertThat(new PlanningEvidencePolicy("审查 Qoder提示词优化资料核对.md 中的产品能力来源")
                .allows(file("docs/research/Qoder提示词优化资料核对.md#chunk-1", ""))).isTrue();
    }

    @Test
    void shouldNotTreatVueMentionAsEvidenceForVuexOrAntDesignVue() {
        var question = new PlanQuestion("state", "迁移后选择什么状态管理方案？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("vuex", "Vuex 4", "Vue 生态方案", "使用 Vuex 4", true, "与 Vue 兼容"),
                new PlanOption("pinia", "Pinia", "Vue 生态方案", "使用 Pinia", false),
                new PlanOption("ant", "Ant Design Vue", "Vue 生态方案", "使用 Ant Design Vue", false)), List.of(), true);
        var aligned = PlanRecommendationAligner.align(question,
                new PlanningProviderRequest("现有 React，迁移到 Vue 3，仅提供方案。", "", List.of(), null));
        assertThat(aligned.options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void shouldNotPromoteAnEnglishTopicInsideABusinessChoiceToRecommendationEvidence() {
        var question = new PlanQuestion("metric", "成功率如何定义？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("plan", "基于 Plan 确认", "确认作为成功", "按确认率统计", true),
                        new PlanOption("api", "API 调用量", "按请求量统计", "按调用量统计", false)), List.of(), true);
        var aligned = PlanRecommendationAligner.align(question,
                new PlanningProviderRequest("审计 services/api 的统计模块，分析 Plan 确认效果和资源消耗趋势", "", List.of(), null));
        assertThat(aligned.options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void shouldNotRecommendChangingTheComparisonBoundaryFromGreaterToGreaterOrEqual() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                List.of("docs/订单方案.txt：订单金额超过五万元时必须由财务复核。"), "COMPLETE", 1, List.of());
        var question = new PlanQuestion("boundary", "五万元边界如何处理？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("inclusive", "大于等于五万元", "包含五万元", "大于等于五万元时财务复核", true),
                        new PlanOption("exclusive", "严格超过五万元", "不包含五万元", "超过五万元时财务复核", false)), List.of(), true);
        var aligned = PlanRecommendationAligner.align(question,
                new PlanningProviderRequest("实现订单审批", "", List.of(), digest));
        assertThat(aligned.options()).filteredOn(option -> option.id().equals("inclusive"))
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void shouldResolveAnExplicitCustomAnswerAndCompareNewEvidenceWithTheChosenValue() {
        var answers = List.of(new PlanAnswer("context-conflict-1", CONFLICT,
                "本次以新审批方案中的五万元阈值为准。"));
        var detector = new ContextConflictDetector();
        assertThat(detector.detect(snapshot(List.of(file("docs/旧规则.txt", "审批阈值：三万元"),
                file("docs/新方案.txt", "审批阈值：五万元"))), answers, "实现订单审批阈值")).isEmpty();
        assertThat(detector.detect(snapshot(List.of(file("docs/旧规则.txt", "审批阈值：三万元"),
                file("docs/新方案.txt", "审批阈值：五万元"), file("docs/财务.txt", "审批阈值：八万元"))),
                answers, "实现订单审批阈值")).singleElement().asString()
                .contains("五万元", "八万元").doesNotContain("三万元");
    }

    @Test
    void shouldNotResolveANegatedConditionalOrMultipleValueAnswer() {
        for (String answer : List.of("不采用五万元", "如果采用五万元还需确认", "三万元或五万元都可以", "暂不确定")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT, answer)));
            assertThat(decisions.resolvesConflict("审批阈值", List.of("三万元", "五万元")))
                    .as(answer).isFalse();
        }
    }

    @Test
    void shouldKeepTheAlternativeValueOfARelevantFieldDuringSecondRetrieval() {
        var findings = new ContextConflictDetector().detect(snapshot(List.of(
                file("研究资料.txt", "研究范围：广东省"), file("新方案.txt", "研究范围：浙江省"))),
                List.of(new PlanAnswer("research-region", "这项研究覆盖哪个地区？", "广东省")),
                "分析心脑血管疾病死亡率\n地区：广东省");
        assertThat(findings).singleElement().asString().contains("广东省", "浙江省");
    }

    @Test
    void shouldMergeOnlyRepeatedConflictEvidenceAndKeepANewPair() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT, "暂不确定")));
        String repeated = CONFLICT.replace("请确认本次采用哪一项。", "用户仍未确定，请核对阈值。");
        String paraphrase = "审批阈值取值冲突：docs/旧规则.txt 为三万元，docs/新方案.txt 为五万元，用户回答“暂不确定”。该值直接决定审批触发条件，需确认采用哪一项。";
        String reminder = "审批阈值冲突未解决：docs/旧规则.txt 为三万元，docs/新方案.txt 为五万元。用户已确认但回答“暂不确定”，需明确采用哪一项，否则无法确定审批触发条件和财务复核范围。";
        String canonicalReminder = CONFLICT.replace("请确认本次采用哪一项。",
                "用户已确认“暂不确定”，请明确本次采用哪一项，否则无法确定财务复核的触发条件。");
        String newPair = CONFLICT.replace("（三万元）", "（八万元）");
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(CONFLICT, repeated, "该问题尚未确定：" + CONFLICT,
                paraphrase, reminder, canonicalReminder, newPair, paraphrase + "还需确认退款订单是否采用此阈值。"),
                List.of(CONFLICT), List.of()).messages())
                .containsExactly(CONFLICT, newPair, paraphrase + "还需确认退款订单是否采用此阈值。");
    }

    @Test
    void shouldKeepAdditionalBusinessConditionsAfterAKnownConflict() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT,
                "本次以新审批方案中的五万元阈值为准。")));
        String newAmount = CONFLICT + "二次检索另有八万元阈值。";
        String newScope = CONFLICT + "另需确认是否适用于退款订单。";
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(CONFLICT, newAmount, newScope),
                List.of(), List.of()).messages()).containsExactly(newAmount, newScope);
    }

    private static ContextSnapshot snapshot(List<FileSnippet> files) {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), files, List.of(), List.of(), "v1");
    }

    private static FileSnippet file(String path, String text) {
        return new FileSnippet(path, "", text, "", false);
    }
}
