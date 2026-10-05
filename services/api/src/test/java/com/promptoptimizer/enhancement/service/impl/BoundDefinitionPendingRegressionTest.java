package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定义题的同题范围可以修饰已登记子项；另一对象、取值和新增条件不能借该范围被归并。
 * 不依赖模型调用，真实整题回放继续由 FidelityReminderReplayTest 保留原断言验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class BoundDefinitionPendingRegressionTest {
    @Test
    void retainsTheFullKnownScopeAnnotationWhenGroupingTheSamePendingSubitem() {
        var explanation = "两组公平对照的具体信息给法及操作设计尚未确定（已确认两组获得同样确认信息、信息量保持一致）。"
                + "需核对材料交付时间。";
        var result = merge(List.of(new PlanAnswer("control", "两组公平对照具体指什么？",
                "保留同样确认信息；具体信息给法及操作设计暂不确定。")), explanation);
        assertThat(result.executionPrerequisites()).hasSize(1);
        assertThat(String.join("\n", result.executionPrerequisites())).contains(explanation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"两组公平对照具体指什么？", "两组公平对照是什么？", "两组公平对照是指什么？"})
    void mergesOnlyTheExplicitOriginalDefinitionScope(String question) {
        var answers = List.of(new PlanAnswer("control", question,
                "两组使用同样确认信息；具体信息给法及操作设计暂不确定。"));
        var finding = "两组公平对照的具体信息给法及操作设计尚未确定。需核对材料交付时间。";
        var result = merge(answers, finding);
        assertThat(result.executionPrerequisites()).hasSize(1);
        assertThat(String.join("\n", result.executionPrerequisites())).contains("需核对材料交付时间。");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "另一研究公平对照的具体信息给法及操作设计尚未确定。",
            "两组公平对照的具体信息给法及操作设计在跨医院时尚未确定。",
            "两组公平对照的具体信息给法及操作设计于2027年尚未确定。",
            "两组公平对照的具体信息给法及操作设计在金额>3000元时尚未确定。",
            "两组公平对照的具体信息给法及操作设计在金额>=3000元时尚未确定。",
            "两组公平对照的具体信息给法及操作设计和伦理批准范围尚未确定。",
            "两组公平对照的具体信息给法及操作设计尚未确定。乙医院的计分权重尚未确定。"
    })
    void preservesANewObjectConditionOrIndependentDecision(String finding) {
        var answers = List.of(new PlanAnswer("control", "两组公平对照具体指什么？",
                "两组使用同样确认信息；具体信息给法及操作设计暂不确定。"));
        assertThat(merge(answers, finding).executionPrerequisites()).hasSize(2).contains(finding);
    }

    private PlanAmbiguityMerger.MergeResult merge(List<PlanAnswer> answers, String finding) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers))
                .merge(List.of(finding), List.of(), List.of());
    }
}
