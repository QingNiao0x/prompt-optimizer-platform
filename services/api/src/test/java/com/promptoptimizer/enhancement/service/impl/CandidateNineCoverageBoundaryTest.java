package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 覆盖判断及提问筛选的独立反例，不能靠少输出要求或少问问题获得通过。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateNineCoverageBoundaryTest {
    @Test
    void shouldCoverCompleteClausesInLongerCurrentExecutionSentences() {
        assertThat(covered("交付方法部分正文，800至1200个汉字，附表另计。不提供代码。",
                "正文800至1200个汉字，附表另计。")).isTrue();
        assertThat(covered("不得编造P值。不得编造参考文献。", "不得编造P值；不得编造参考文献。")).isTrue();
        assertThat(covered("不得编造P值。", "不得编造P值；不得编造参考文献。")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后确认，甲院2024年使用R。", "乙院2024年使用R。", "甲院2025年使用R。",
            "甲院2024年使用RStudio。", "例如：甲院2024年使用R。", "> 甲院2024年使用R。",
            "```text\n甲院2024年使用R。\n```", "甲院2024年不使用R。", "甲院2024年使用R，但乙院另需确认。"})
    void shouldKeepAnotherObjectYearConditionAndNegation(String body) {
        // 最后一个变体完整覆盖甲院，但仍必须保留独立乙院要求。
        String rule = body.contains("乙院另需") ? "乙院2024年使用R。" : "甲院2024年使用R。";
        assertThat(covered(body, rule)).isFalse();
    }

    @Test
    void shouldKeepNewBoundsAndPartiallyConfirmedParameters() {
        assertThat(covered("甲指标采用年龄标准化。", "甲指标采用2020年标准人口。")).isFalse();
        assertThat(covered("超时5000毫秒。", "超时500毫秒。")).isFalse();
        assertThat(covered("超时500毫秒。", "超时500毫秒且保留异常统计日志。")).isFalse();
        assertThat(covered("若有授权，导出甲院数据。", "导出甲院数据。")).isFalse();
        assertThat(covered("禁止不提供代码。", "不提供代码。")).isFalse();
        assertThat(covered("不得不提供代码。", "不提供代码。")).isFalse();
    }

    @Test
    void shouldAppendOnlyMissingIndependentAnswerClauses() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务",
                "由于用户未确认参数，请根据原始研究目标提出可开展的推荐设计并说明适用条件。"));
        var missing = PlanDecisionIntegrator.uncoveredRuleClauses(sections,
                "请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。");
        assertThat(missing).hasSize(2).allSatisfy(clause -> assertThat(clause).doesNotContain("请根据原始研究目标"));
        assertThat(String.join("", missing)).contains("我没有额外真实数据", "不确认任何既有参数");
        assertThat(PlanDecisionIntegrator.uncoveredRuleClauses(sections,
                "如果以后确认甲院参数，提出方案；否则保留甲院原参数。"))
                .containsExactly("如果以后确认甲院参数，提出方案；否则保留甲院原参数。");
    }

    @Test
    void shouldHandleOnlyTheActualRoutineBodyCountQuestion() {
        String raw = "撰写研究设计的方法部分。正文800至1200个汉字，附表另计。";
        var question = question("正文 800 至 1200 个汉字是否包含标题、摘要和参考文献？", "");
        var request = new PlanningProviderRequest(raw, "", List.of(), null);
        assertThat(new PlanQuestionFilter().filter(List.of(question), request)).isEmpty();
        assertThat(new PlanQuestionFilter().filter(List.of(question),
                new PlanningProviderRequest(raw + "按投稿期刊规范计数。", "", List.of(), null))).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(question("正文 800 至 1200 个汉字是否包含标题、摘要和参考文献？",
                "另外需要确认另一机构的投稿范围。")), request)).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(question("正文 900 至 1200 个汉字是否包含标题、摘要和参考文献？", "")), request)).hasSize(1);
    }

    /** 当前执行要求仅由四要素正文证明，示例、引文和历史片段不能证明覆盖。 */
    private boolean covered(String body, String rule) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", body));
        return PlanDecisionIntegrator.coveredRule(sections, rule);
    }
    private PlanQuestion question(String text, String hint) {
        return new PlanQuestion("scope", text, hint, PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
