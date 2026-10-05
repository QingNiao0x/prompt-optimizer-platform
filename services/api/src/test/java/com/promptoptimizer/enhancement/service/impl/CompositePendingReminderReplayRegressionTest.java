package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结候选02科研和医院的整题复写，按完整回答核对多子项归并与对象、条件保护。
 * 本测试仅重放真实模型的合成资料结果，不调用模型，也不替代专业业务验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CompositePendingReminderReplayRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUESTION = "对于甲医院，观察窗口和爽约率分母应如何确定？";
    private static final String ANSWER = "观察窗口尚未确定，不默认任何时长；爽约率分母是否包含UNKNOWN尚未确定，不计算真实医院指标。";

    @ParameterizedTest
    @ValueSource(strings = {
            "CV-SCALE-L-deepseek_deepseek-flash",
            "CV-SCALE-L-deepseek_deepseek-v4-pro"
    })
    void replaysActualResearchCompositeRemindersAsFourIndependentUndecidedGroups(String sampleId) throws Exception {
        JsonNode sample = sample(sampleId);
        var result = merge(answers(sample), findings(sample));
        assertThat(result.executionPrerequisites()).hasSize(4);
        String body = String.join("\n", result.executionPrerequisites());
        assertThat(body).contains("评分锚点", "最大交互轮次", "终止规则", "分歧处理", "一致性评价指标", "不锁定");
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("评分锚点"))).hasSize(1);
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("一致性评价指标"))).hasSize(1);
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("分歧处理"))).hasSize(1);
        // 已定顺序尺度不变，未知等级和锚点不能因为跨题合并变成某个默认量表。
        assertThat(ConfirmedDecisionSet.from(answers(sample)).knownDecisions())
                .anyMatch(decision -> decision.answer().contains("顺序尺度"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CV-HOSPITAL-M-deepseek_deepseek-flash",
            "CV-HOSPITAL-M-deepseek_deepseek-v4-pro"
    })
    void replaysActualHospitalWholeAnswerWithoutLosingFiveDistinctUndecidedFacets(String sampleId) throws Exception {
        JsonNode sample = sample(sampleId);
        var result = merge(answers(sample), findings(sample));
        // 观察窗口、UNKNOWN分母、资料适用/批准、最终取消边界、主统计单位不能按医院类别压成一项。
        assertThat(result.executionPrerequisites()).hasSize(5);
        String body = String.join("\n", result.executionPrerequisites());
        assertThat(body).contains("观察窗口", "分母", "UNKNOWN", "24小时", "12小时", "批准", "取消免责边界", "主统计单位");
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("观察窗口"))).hasSize(1);
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("其是否进入分母"))).hasSize(1);
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("主统计单位"))).hasSize(1);
        assertThat(body).contains("不默认", "不直接计为爽约");
    }

    @Test
    void mergesAnExactBareOriginalQuestionAndCompleteAnswerAcrossEveryRegisteredSubitem() {
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(QUESTION + ANSWER));
        assertThat(result.executionPrerequisites()).hasSize(2);
        assertThat(String.join("\n", result.executionPrerequisites()))
                .contains("甲医院", "观察窗口", "分母是否包含UNKNOWN", "不计算真实医院指标", "不默认任何时长");
    }

    @Test
    void mergesTheLegacyOriginalQuestionAndCompleteAnswerWithoutSelectingItsUnknowns() {
        String replay = "该问题尚未确定：" + QUESTION + " 用户说明：" + ANSWER;
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(replay));
        assertThat(result.executionPrerequisites()).hasSize(2);
        assertThat(String.join("\n", result.executionPrerequisites()))
                .contains("尚未确定", "不默认任何时长", "不计算真实医院指标");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "乙医院观察窗口尚未确定。",
            "甲医院预约金额>3000元时观察窗口尚未确定。",
            "甲医院预约金额>=3000元时观察窗口尚未确定。",
            "甲医院预约金额!=3000元时观察窗口尚未确定。",
            "甲医院审批生效时间尚未确定。",
            "甲医院STATUS_A映射尚未确定。",
            "甲医院status_a映射尚未确定。"
    })
    void preservesEveryNewObjectConditionComparatorAndAttributeAfterAnExactWholeAnswerReplay(String extra) {
        String finding = QUESTION + ANSWER + extra;
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(finding));
        String body = String.join("\n", result.executionPrerequisites());
        assertThat(body).contains(extra, "观察窗口", "分母是否包含UNKNOWN");
        assertThat(result.executionPrerequisites()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void doesNotUseAnotherObjectsIdenticalPendingAnswerToMergeANewOriginalQuestion() {
        var result = merge(List.of(new PlanAnswer("hospital-a", QUESTION, ANSWER)),
                List.of("对于乙医院，观察窗口和爽约率分母应如何确定？" + ANSWER));
        assertThat(result.executionPrerequisites()).hasSizeGreaterThanOrEqualTo(3)
                .anyMatch(text -> text.contains("乙医院"))
                .anyMatch(text -> text.contains("甲医院"));
    }

    @Test
    void retainsAllNewConditionsAndValuesWithoutInterpretingThemAsAnExplanation() {
        String extra = "甲医院观察窗口需要在就诊后48小时与72小时之间选择，仍未确定。";
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(QUESTION + ANSWER + extra));
        assertThat(String.join("\n", result.executionPrerequisites())).contains(extra, "48小时", "72小时");
        assertThat(result.executionPrerequisites()).hasSizeGreaterThanOrEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "三名评审的分歧处理流程和一致性评价指标尚未确定。",
            "两名评审在终审时的分歧处理流程和一致性评价指标尚未确定。",
            "两名评审的分歧处理流程和一致性评价指标在金额>=3000元时尚未确定。",
            "两名评审的分歧处理流程和一致性评价指标以及仲裁权限尚未确定。"
    })
    void rejectsAnUncoveredOwnerConditionOrThirdPropertyInACompositeParaphrase(String finding) {
        var result = merge(List.of(new PlanAnswer("review", "两名评审的分歧应如何处理？",
                "分歧处理流程暂不确定；一致性评价指标尚未确定，不锁定某个指标。")), List.of(finding));
        assertThat(result.executionPrerequisites()).hasSize(3).contains(finding);
    }

    @Test
    void preservesANewGenericUnknownConditionEvenWhenTheOriginalQuestionIsRepeatedExactly() {
        String finding = QUESTION + "当前尚未决定，不默认任何时长，但必须排除节假日；"
                + "爽约率分母是否包含UNKNOWN尚未确定，不计算真实医院指标。";
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(finding));
        assertThat(result.executionPrerequisites()).hasSizeGreaterThanOrEqualTo(3).contains(finding);
    }

    @Test
    void retainsANewConcreteExplanationAfterACompleteReplayWithoutInventingAKnownChoice() {
        String extra = "展示时保留原始代码STATUS_A及比较表达式amount>=3000，不改写为amount>3000。";
        var result = merge(List.of(new PlanAnswer("hospital", QUESTION, ANSWER)), List.of(QUESTION + ANSWER + extra));
        assertThat(result.executionPrerequisites()).hasSize(2);
        assertThat(String.join("\n", result.executionPrerequisites())).contains(extra, "尚未确定");
        assertThat(ConfirmedDecisionSet.from(List.of(new PlanAnswer("hospital", QUESTION, ANSWER))).knownDecisions()).isEmpty();
    }

    /** 读取已冻结的完整题目、回答及提醒，避免只拿提问标题构造虚假的去重成功。 */
    private JsonNode sample(String sampleId) throws Exception {
        try (var stream = getClass().getResourceAsStream("/plan-regression/pending-composite-full-20261005.json")) {
            assertThat(stream).isNotNull();
            for (JsonNode sample : MAPPER.readTree(stream).path("samples")) {
                if (sampleId.equals(sample.path("id").asText())) return sample;
            }
        }
        throw new IllegalArgumentException("未找到冻结提醒样例：" + sampleId);
    }

    /** 只使用真实返回问题ID及逐题审核的回答，不向生产逻辑注入额外已知事实。 */
    private List<PlanAnswer> answers(JsonNode sample) {
        return MAPPER.convertValue(sample.path("answers"), MAPPER.getTypeFactory()
                .constructCollectionType(List.class, PlanAnswer.class));
    }

    private List<String> findings(JsonNode sample) {
        return MAPPER.convertValue(sample.path("findings"), MAPPER.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private PlanAmbiguityMerger.MergeResult merge(List<PlanAnswer> answers, List<String> findings) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(findings, List.of(), List.of());
    }
}
