package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实提醒同一条目中的状态只需说明一次；依赖解释、新对象、年份和条件不能随复述消失。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class BoundStateReminderCompactionTest {
    private static final String QUESTION = "甲院与 hospital_id 的对应关系是什么？";
    private static final String ANSWER = "暂不确定。甲院与hospital_id的对应关系尚未核实；"
            + "A/B取值集合不能证明名称与代码的关系，也不能用另一院排除推定。";

    @Test
    void actualBoundModelRestatementKeepsOneStateAndTheNewDependencyExplanation() {
        var result = merge("该问题尚未确定：" + QUESTION + " 用户说明：" + ANSWER.substring(5)
                + " 补充说明：甲院与 hospital_id 的对应关系尚未核实，该对应关系影响甲院数据筛选与分组，需院方确认。");
        assertThat(result).hasSize(1);
        String text = result.getFirst().replaceAll("\\s+", "");
        assertThat(occurrences(text, "甲院与hospital_id的对应关系尚未核实")).isEqualTo(1);
        assertThat(text).contains("影响甲院数据筛选与分组", "需院方确认", "不能用另一院排除推定");
    }

    @Test
    void anotherHospitalAndYearAreStillSeparateUnresolvedDetails() {
        var result = merge("甲院与 hospital_id 的对应关系尚未核实，该对应关系影响甲院数据筛选。"
                + "乙院2026年与hospital_id的对应关系尚未核实，需另一份资料核对。");
        assertThat(String.join(" ", result)).contains("乙院2026年", "另一份资料核对", "甲院数据筛选");
    }

    @Test
    void newApprovalConditionAndValueAreRetainedAfterRepeatedState() {
        var result = merge("该问题尚未确定：" + QUESTION + " 用户说明：" + ANSWER.substring(5)
                + " 补充说明：甲院与 hospital_id 的对应关系尚未核实，只有院方审批后才能采用新映射；"
                + "若以后确认甲院为B，90分钟阈值仅作用于该院，不代表乙院已确认。");
        assertThat(String.join(" ", result)).contains("只有院方审批后", "若以后确认甲院为B", "不代表乙院已确认");
    }

    @Test
    void futureAndQuotedStatementsDoNotBecomeKnownCurrentState() {
        var result = merge("如果以后确认甲院与hospital_id的对应关系尚未核实，应重新核对资料。"
                + "“甲院与hospital_id的对应关系尚未核实”是示例，不代表另一个年份已有结论。");
        assertThat(String.join(" ", result)).contains("如果以后确认", "是示例", "另一个年份");
    }

    @Test
    void retainsTheEntireNewExplanationAndConditionalDependentDecision() {
        String detail = "甲院与hospital_id的对应关系尚未核实，影响分组；"
                + "若以后确认甲院为B，只对甲院使用90分钟；另外乙院审批状态尚未确认。";
        assertThat(BoundStateReminderCompactor.compact(ANSWER, detail)).isEqualTo(
                "影响分组；若以后确认甲院为B，只对甲院使用90分钟；另外乙院审批状态尚未确认。");
    }

    @Test
    void neverBorrowsStateFromAnotherOwnerYearMetricOrOperator() {
        String known = "甲院2025年等待>=90分钟的分母尚未核实。";
        for (String detail : List.of("乙院2025年等待>=90分钟的分母尚未核实，需资料。",
                "甲院2026年等待>=90分钟的分母尚未核实，需资料。",
                "甲院2025年等待>90分钟的分母尚未核实，需资料。",
                "甲院2025年等待>=90分钟的阈值尚未核实，需资料。")) {
            assertThat(BoundStateReminderCompactor.compact(known, detail)).as(detail).isEqualTo(detail);
        }
    }

    @Test
    void verificationAndUserSelectionRemainDifferentStates() {
        String detail = "甲院与hospital_id的对应关系尚未选定，仍需用户选择。";
        assertThat(BoundStateReminderCompactor.compact(ANSWER, detail)).isEqualTo(detail);
    }

    @Test
    void quotedOrConditionalKnownTextCannotEraseCurrentMissingEvidence() {
        String detail = "甲院与hospital_id的对应关系尚未核实，影响筛选。";
        for (String known : List.of("如果甲院与hospital_id的对应关系尚未核实，需停止。",
                "“甲院与hospital_id的对应关系尚未核实”。", "> 甲院与hospital_id的对应关系尚未核实。")) {
            assertThat(BoundStateReminderCompactor.compact(known, detail)).as(known).isEqualTo(detail);
        }
    }

    @Test
    void aCompleteRepeatedStateCanDisappearWithoutRemovingItsBoundAnswer() {
        assertThat(BoundStateReminderCompactor.compact(ANSWER, "甲院与 hospital_id 的对应关系未核实。")).isEmpty();
        assertThat(ANSWER).contains("尚未核实", "不能用另一院排除推定");
    }

    @Test
    void anUnrelatedItemAndDirectEnhancementRemainUntouched() {
        String detail = "甲院与hospital_id的对应关系尚未核实，影响筛选。";
        assertThat(new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of()))
                .merge(List.of(detail), List.of(), List.of()).executionPrerequisites()).containsExactly(detail);
        assertThat(BoundStateReminderCompactor.compact("乙院观察窗口尚未核实。", detail)).isEqualTo(detail);
    }

    @Test
    void keepsLineBreaksAndUnchangedTextFormatting() {
        String untouched = "  乙院观察窗口尚未核实。\n  下一年度的审批状态尚未确认。\n";
        assertThat(BoundStateReminderCompactor.compact(ANSWER, untouched)).isEqualTo(untouched);
        assertThat(BoundStateReminderCompactor.compact(ANSWER,
                "甲院与hospital_id的对应关系尚未核实，影响筛选。\n  乙院观察窗口尚未核实。"))
                .isEqualTo("影响筛选。\n  乙院观察窗口尚未核实。");
    }

    private static List<String> merge(String finding) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("entity-id-relation-1", QUESTION, ANSWER)));
        return new PlanAmbiguityMerger(decisions).merge(List.of(finding), List.of(), List.of()).executionPrerequisites();
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
    }
}
