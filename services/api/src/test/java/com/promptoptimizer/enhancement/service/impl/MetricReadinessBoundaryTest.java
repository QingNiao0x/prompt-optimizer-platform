package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 区分单参数已确认与整项计算就绪，表格不得同时声明必要依赖未决且可直接计算。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MetricReadinessBoundaryTest {
    private static final String HEADER = "| 指标 | 分母或所需参数 | 参数状态 | 计算前提 | 计算状态 |\n"
            + "| --- | --- | --- | --- | --- |\n";

    @Test
    void aChosenThresholdDoesNotSatisfyAnUnknownInstitutionOrWindow() {
        var contract = contract();
        for (String dependency : List.of("甲院医院代码待确认", "甲院观察窗口尚未确定", "甲院分母未决")) {
            assertThatThrownBy(() -> contract.validate(row("已确认", dependency, "可直接计算"), "sections.OUTPUT"))
                    .as(dependency).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void preservesTheChosenParameterWhileWaitingForItsActualDependencies() {
        contract().validate(row("已确认", "甲院医院代码待确认", "等待确认后计算"), "sections.OUTPUT");
    }

    @Test
    void aFutureComputationStatementIsNotCurrentReadiness() {
        contract().validate(row("已确认", "甲院观察窗口尚未确定", "确认后可计算"), "sections.OUTPUT");
        contract().validate(row("已确认", "甲院观察窗口尚未确定", "可计算（仅在前提确认后）"), "sections.OUTPUT");
    }

    @Test
    void explicitNoPendingItemsDoesNotCreateAnUnknownDependency() {
        contract().validate(row("已确认", "无待确认项", "可计算"), "sections.OUTPUT");
    }

    @Test
    void unrelatedWorkTablesDoNotBecomeMetricComputations() {
        contract().validate("| 工作事项 | 负责人 | 计算前提 | 计算状态 |\n| --- | --- | --- | --- |\n"
                + "| 文档排版 | 待确认 | 无 | 可执行 |", "sections.OUTPUT");
    }

    @Test
    void quotedAndFencedCounterexamplesArePreservedAsMaterials() {
        String contradiction = row("已确认", "甲院医院代码待确认", "可直接计算");
        contract().validate("~~~text\n" + contradiction + "\n~~~", "sections.OUTPUT");
        contract().validate(contradiction.replaceAll("(?m)^", "> "), "sections.OUTPUT");
    }

    private static UnresolvedDecisionContract contract() {
        return UnresolvedDecisionContract.from("交付指标表。甲院异常等待阈值采用90分钟。",
                ConfirmedDecisionSet.from(List.of()));
    }

    private static String row(String state, String dependency, String readiness) {
        return HEADER + "| 甲院异常等待 | 90分钟 | " + state + " | " + dependency + " | " + readiness + " |";
    }
}
