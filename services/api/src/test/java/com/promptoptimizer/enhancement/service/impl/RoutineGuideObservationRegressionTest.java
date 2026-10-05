package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放已归档的指南界面事实追问，区别产品核查前提与必须由用户决定的新要求。
 * 本类不读取真实项目资料，不请求外部模型，不能代替真实候选验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RoutineGuideObservationRegressionTest {
    private static final String RAW = """
            请根据附件的产品功能说明和项目代码，为首次使用提示词工具的非技术用户编写中文操作指南。
            交付物只有可直接发布的指南正文，内容包括开始使用、添加上下文、选择是否使用Plan、
            回答确认问题、核对结果、编辑和复制、查看历史、常见问题与权限边界。
            保存编辑、取消编辑、再次增强这些能力只介绍附件明确提供的行为，不虚构自动撤销、
            自动恢复任何历史版本或跨账号共享记录。历史的删除是逻辑删除，不要说数据立即从物理库消失。
            常见问题覆盖当模型失败时如何保留需求后重试。
            材料如果不够证明某按钮位置，写清操作目的并说明以当前界面为准，不编造按钮文本和截图。
            """;

    @Test
    void delegatesAllFiveNewActualCurrentWorkflowQuestionsWithoutSelectingTheirCandidateFacts() throws Exception {
        // 首屏、问答位置、文档提取步骤、列表字段与编辑效果均来自真实 Flash 返回；保留完整候选参与校验。
        try (var stream = getClass().getResourceAsStream("/plan-regression/guide-observation-full-20261005.json")) {
            var mapper = new ObjectMapper();
            var replay = mapper.readTree(stream);
            List<PlanQuestion> questions = mapper.convertValue(replay.path("questions"), mapper.getTypeFactory()
                    .constructCollectionType(List.class, PlanQuestion.class));
            assertThat(questions).hasSize(5);
            assertThat(new PlanQuestionFilter().filter(questions,
                    new PlanningProviderRequest(replay.path("raw").asText(), "", List.of()))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "当前账户首次进入产品看到哪些内容？",
            "保存草稿后，当前页面实际保留哪一份内容？",
            "资料已提交以后，界面将怎样显示处理结果？",
            "指南应描述用户实际操作时出现的哪些页面？"
    })
    void delegatesObservableWorkflowFactsWithoutRequiringAProductFeatureKeyword(String text) {
        var current = question(text, "附件没有说明当前操作表现，请以当前界面核查为准。", "核查实际界面再写指南。");
        assertThat(RoutineGuideDecision.delegated(current, RAW)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "指南中编辑页面未来要采用哪个保存方案？",
            "首次进入产品时，你希望用户先提供哪些身份资料？",
            "指南中的新增上传权限应该怎样设计？",
            "用户上传后，是否新增三次重试的额度？",
            "历史列表实际展示哪些内容，另外哪些人能读取其他账户记录？"
    })
    void retainsDesignAndIndependentBusinessChoicesEvenWhenTheMainQuestionLooksLikeAnObservation(String text) {
        assertThat(RoutineGuideDecision.delegated(question(text,
                "附件没有说明当前操作表现。", "保持未决。"), RAW)).isFalse();
    }

    @Test
    void delegatesThreeActualCandidate04ObservationsWithoutSelectingAnyUnverifiedAnswer() {
        // 题干、缺资料提示及候选答案来自候选04的实际Pro返回；历史失败证据保持原样。
        var trigger = question("用户开启 Plan 后，系统如何开始提问？",
                "指南需要告诉读者开启后会发生什么，但材料未说明具体交互方式。",
                "开启 Plan 后，系统会自动显示需要确认的问题。",
                "开启 Plan 后，需要点击“开始提问”按钮才会显示需要确认的问题。",
                "开启 Plan 后，用户需要主动触发提问，具体操作以当前界面为准。");
        var deletion = question("历史记录删除后，用户界面会立即隐藏该记录吗？",
                "指南需要说明删除后的可见性，材料仅说明逻辑删除，未说明界面表现。",
                "删除后，该记录会立即从历史列表中隐藏。",
                "删除后，需要刷新页面该记录才会从历史列表中隐藏。",
                "删除后，该记录将从历史列表中移除，具体界面更新方式以当前界面为准。");
        var retry = question("模型失败后，用户如何保留需求并重试？",
                "常见问题需要说明重试步骤，但材料未提供具体操作。",
                "模型失败后，您已填写的内容会保留，可以直接再次点击“开始增强”重试。",
                "模型失败后，请复制您原来的需求，重新粘贴后再次点击“开始增强”重试。",
                "模型失败后，您可以保留原有需求并重新尝试增强。");
        assertThat(new PlanQuestionFilter().filter(List.of(trigger, deletion, retry),
                new PlanningProviderRequest(RAW, "", List.of()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "启用计划确认后，页面怎样展示确认问题？",
            "用户删除历史条目后，是否需要刷新列表才能看到变化？",
            "增强请求失败时，已输入的需求还会留在输入框中吗？",
            "编辑结果取消后，界面会显示哪一版内容？",
            "上传资料后，用户怎样查看当前解析状态？"
    })
    void delegatesCurrentInterfaceParaphrasesOnlyWhenTheUserAlreadyProvidedAnEvidenceFallback(String text) {
        assertThat(RoutineGuideDecision.delegated(question(text,
                "附件未说明当前界面的具体操作，请依据当前界面核查。", "核查实际界面再写指南。"), RAW)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "另外需确认管理员是否批准公开发布。",
            "另外需确认患者材料的数据访问授权。",
            "另需决定采用哪个法域。",
            "还需确定新增的收费规则。",
            "观察窗口与统计分母仍未知。",
            "另外需确认报告语言与交付对象。",
            "失败后重试3次。",
            "延时超过30秒后隐藏该记录。",
            "退款金额>=3000元时采用另一流程。"
    })
    void retainsIndependentBusinessProfessionalNumericAndAuthorizationConditionsInEveryQuestionField(String condition) {
        var candidate = question("用户开启 Plan 后，系统如何开始提问？",
                "资料未说明界面操作。", "核查当前界面。", condition);
        assertThat(RoutineGuideDecision.delegated(candidate, RAW)).as(condition).isFalse();
        var hint = question(candidate.question(), "资料未说明界面操作。" + condition, "核查当前界面。");
        assertThat(RoutineGuideDecision.delegated(hint, RAW)).as(condition).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请先向我确认未说明的具体功能，再写指南。",
            "请先询问我删除后的界面行为。",
            "请先让我确认删除后的界面表现。",
            "这些未核实的产品行为需要由我选择，不要自行处理。",
            "这些未核实的产品行为需要我先确认，不要自行处理。",
            "需要先让我决定失败后如何处理。"
    })
    void retainsExplicitRequestsForAUserFactCheckOrChoice(String explicit) {
        assertThat(RoutineGuideDecision.delegated(question("模型失败后，用户如何保留需求并重试？",
                "材料未提供具体操作。", "核查实际界面。"), RAW + explicit)).isFalse();
    }

    @Test
    void retainsObservationsWhenThereIsNoEvidenceGapOrNoTruthfulInterfaceFallback() {
        var known = question("用户开启 Plan 后，系统如何开始提问？", "材料已说明现行行为。", "按材料写。");
        assertThat(RoutineGuideDecision.delegated(known, RAW)).isFalse();
        var missing = question(known.question(), "资料未说明具体操作。", "按资料写。");
        assertThat(RoutineGuideDecision.delegated(missing, "请编写用户指南。" )).isFalse();
        assertThat(RoutineGuideDecision.delegated(missing, "请编写用户指南，不编造产品能力。" )).isFalse();
    }

    @Test
    void retainsIndependentDecisionsInExamplesAndRecommendationReasons() {
        var base = question("用户开启 Plan 后，系统如何开始提问？", "资料未说明具体交互方式。", "核查实际界面。");
        var example = new PlanQuestion(base.id(), base.question(), base.hint(), base.type(), base.options(),
                List.of("另外需确认报告是否公开发布。"), true);
        assertThat(RoutineGuideDecision.delegated(example, RAW)).isFalse();
        var recommendation = new PlanQuestion(base.id(), base.question(), base.hint(), base.type(),
                List.of(new PlanOption("observe", "按界面核查", "", "核查实际界面。", true,
                        "另外需确认医疗数据访问授权。")), List.of(), true);
        assertThat(RoutineGuideDecision.delegated(recommendation, RAW)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "本次希望删除后立即隐藏，还是刷新后隐藏？",
            "是否新增自动恢复历史记录的功能？",
            "应如何设计模型失败后的重试策略？",
            "哪些用户可以删除其他人的历史记录？",
            "用户授权医院资料上传的有效期应怎样确定？",
            "模型失败后的赔偿与收费政策采用哪一种？"
    })
    void retainsNewProductBehaviorAccessRightsAndBusinessPolicyChoices(String text) {
        assertThat(RoutineGuideDecision.delegated(question(text,
                "资料未说明相关行为。", "保持未决。"), RAW)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请开发Plan确认模块并实现界面触发与失败重试，不编造已有行为。",
            "请撰写论文研究方案，分析模型失败后的重试机制，不编造实验结果。",
            "请整理医院运营统计分析方案，保留未知分母，不编造结果。",
            "请撰写法务材料整理报告，保留法域未知，不编造法律意见。"
    })
    void doesNotDelegateQuestionsForAnotherDeliveryGoal(String raw) {
        assertThat(RoutineGuideDecision.delegated(question("模型失败后，用户如何保留需求并重试？",
                "材料未提供具体操作。", "保持未知。"), raw + "以当前界面为准。")).isFalse();
    }

    /** 构建题目完整细节，确认独立条件即使藏在候选答案里也不能被删题。 */
    private static PlanQuestion question(String text, String hint, String... answers) {
        var options = java.util.stream.IntStream.range(0, answers.length)
                .mapToObj(index -> new PlanOption("option_" + index, "可选表达", "", answers[index], false))
                .toList();
        return new PlanQuestion("observation", text, hint, PlanQuestionType.SINGLE_CHOICE,
                options, List.of(), true);
    }
}
