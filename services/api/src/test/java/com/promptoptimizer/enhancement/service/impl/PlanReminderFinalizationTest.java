package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来自真实医院回答的概述与逐项重叠；不同年份、新条件和代码不能被精简吞掉。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanReminderFinalizationTest {
    private static final String RAW = "甲院和乙院的候诊时间比较。两院需要独立计算后再比较。"
            + "两院比较观察窗口尚未确定。异常等待阈值尚未确定。交付指标表。";
    private static final String SUMMARY = "乙院阈值与两院观察窗口分别仍未决定。";

    @Test
    void removesAPureSummaryOnlyWhenEveryIndependentPartAlreadyHasAReminder() {
        var result = merge(parameters(SUMMARY), List.of());
        assertThat(result.messages()).hasSize(3).noneMatch(value -> value.contains(SUMMARY));
        assertThat(String.join("\n", result.executionPrerequisites()))
                .contains("甲院的比较观察窗口", "乙院的比较观察窗口", "乙院的异常等待阈值");
    }

    @Test
    void preservesNewYearsAndApprovalDetailsInAnOtherwiseRepeatedSummary() {
        for (String newDetail : List.of("2026年的批准状态尚未确定。", "新增丙院阈值仍未确定。")) {
            var result = merge(parameters(SUMMARY + newDetail), List.of());
            assertThat(String.join("\n", result.executionPrerequisites())).contains(newDetail);
        }
    }

    @Test
    void anUncoveredPartKeepsItsWholeSummary() {
        var incomplete = parameters(SUMMARY).stream().filter(answer -> !answer.questionId().equals("b-window")).toList();
        assertThat(String.join("\n", merge(incomplete, List.of()).executionPrerequisites())).contains(SUMMARY);
    }

    @Test
    void actualIndependentReminderCoverageDoesNotDependOnTheOriginalPendingWording() {
        String raw = "请为甲院和乙院制定候诊时间比较方案。两院需要独立计算后再比较。"
                + "两院的比较观察窗口都没有定，但两者可能不同，请分别确认。异常等待的阈值也没有定。";
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(parameters(SUMMARY)), raw)
                .merge(List.of(), List.of(), List.of());
        assertThat(result.messages()).hasSize(3).noneMatch(value -> value.contains(SUMMARY));
    }

    @Test
    void sameMappingUnknownMergesButKeepsItsActualImpact() {
        var answer = new PlanAnswer("a-mapping", "甲院与 hospital_id 的对应关系是什么？",
                "甲院与hospital_id的对应关系尚未核实；不能用另一院排除推定。");
        var result = merge(List.of(answer), List.of("甲院与 hospital_id 的对应关系尚未核实，影响按医院分组统计和两院比较。"));
        assertThat(result.messages()).hasSize(1);
        assertThat(result.executionPrerequisites().getFirst()).contains("不能用另一院排除推定", "影响按医院分组统计和两院比较");
    }

    @Test
    void anotherMappingFieldYearOrInstitutionIsNotAnOldMappingQuestion() {
        var answer = new PlanAnswer("a-mapping", "甲院与 hospital_id 的对应关系是什么？", "暂不确定。");
        for (String newIssue : List.of("乙院与hospital_id的对应关系尚未核实。", "甲院2026年与hospital_id的对应关系尚未核实。",
                "甲院与department_id的对应关系尚未核实。", "甲院与hospital_id的对应关系尚未核实，新增跨院共享仍需审批。")) {
            assertThat(String.join("\n", merge(List.of(answer), List.of(newIssue)).executionPrerequisites()))
                    .contains(newIssue);
        }
    }

    @Test
    void anUnverifiedFactNeverBecomesAConfirmedChoiceOrRetrievalSelection() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("mapping", "甲院代码是什么？",
                "甲院与hospital_id的对应关系尚未核实；不能用另一院排除推定。")));
        assertThat(decisions.knownDecisions()).isEmpty();
        assertThat(decisions.retrievalQuery("制定方案")).isEqualTo("制定方案");
        assertThat(decisions.pendingDecisions()).hasSize(1);
        assertThat(PlanAnswerSemantics.unresolved("如果以后确认，尚未核实的代码再复查。")).isFalse();
        assertThat(PlanAnswerSemantics.unresolved("对应关系未核实前不得按医院计算。")).isFalse();
    }

    @Test
    void aColonCanIntroduceAnExplanationWithoutRepeatingTheBoundState() {
        String detail = "甲院与 hospital_id 的对应关系尚未核实：A/B取值集合不能证明关系，该对应影响分组。";
        assertThat(BoundStateReminderCompactor.compact("甲院与hospital_id的对应关系尚未核实。", detail))
                .isEqualTo("A/B取值集合不能证明关系，该对应影响分组。");
    }

    @Test
    void neverDeduplicatesFencedExamplesUsingTildesOrShortNestedMarkers() {
        String rule = "未知分母必须保持待确认，不得自行采用惯例。";
        for (String fenced : List.of("~~~text\n" + rule + "\n" + rule + "\n~~~",
                "````text\n```\n" + rule + "\n" + rule + "\n````")) {
            var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
            sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", rule));
            sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", fenced));
            ExecutionRuleCompactor.compact(sections);
            assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo(fenced);
        }
    }

    private static List<PlanAnswer> parameters(String summary) {
        return List.of(new PlanAnswer("a-window", "甲院的比较观察窗口如何定义？", "暂不确定。"),
                new PlanAnswer("b-window", "乙院的比较观察窗口如何定义？", "暂不确定。"),
                new PlanAnswer("a-threshold", "甲院异常等待阈值是什么？", "本次仅确认甲院异常等待阈值为90分钟；" + summary),
                new PlanAnswer("b-threshold", "乙院的异常等待阈值是多少？",
                        "暂不确定。乙院的异常等待阈值尚未决定，不能沿用甲院90分钟；相关计算等待乙院阈值确认。"));
    }

    private static PlanAmbiguityMerger.MergeResult merge(List<PlanAnswer> answers, List<String> findings) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers), RAW).merge(findings, List.of(), List.of());
    }
}
