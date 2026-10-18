package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放265的候选条件反问并保护独立选择；指导传递测试不代替真实作品质量验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateNineteenBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capturedPlanningResponseKeepsRealSourceAndTerminationQuestions() throws Exception {
        var fixture = fixture();
        var questions = JSON.readerForListOf(PlanQuestion.class).<List<PlanQuestion>>readValue(fixture.path("questions"));
        assertThat(PlanningDecisionPolicy.from(new PlanningProviderRequest(fixture.path("rawPrompt").asText(), "", List.of()))
                .resolvedOrDelegated(questions.getLast())).as("permission scope should be recognized before pipeline filters").isTrue();
        var filtered = filter(questions, fixture.path("rawPrompt").asText());
        assertThat(filtered).extracting(PlanQuestion::id).containsExactly("plan_rounds", "task_source");
        assertThat(filtered.getFirst()).isEqualTo(questions.getFirst());
        assertThat(filtered.getLast()).isEqualTo(questions.get(1));
        // 原件及候选选项保持原样；过滤没有制造选项确认、实际来源或终止轮数。
        assertThat(questions).hasSize(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "另外是否允许使用真实参与者资料？", "如果资料来源冲突，是否允许择一？",
            "另一研究是否采用同一方法？", "阈值采用0.05还是0.01？", "是否有额外预算？",
            "候选统计检验实际采用Kruskal-Wallis检验。", "标准人口由用户选择。",
            "学校要求先确认候选列表。", "请同时确认实际使用的数据库。", "请将任务限定为住院患者。"
    })
    void anIndependentChoiceInAnyVisiblePartCannotDisappear(String detail) throws Exception {
        var candidate = scopeQuestion();
        var option = new PlanOption("new", "新选择", detail, detail, false, "");
        var withHint = new PlanQuestion(candidate.id(), candidate.question(), candidate.hint() + detail,
                candidate.type(), candidate.options(), List.of(), true);
        var withOption = new PlanQuestion(candidate.id(), candidate.question(), candidate.hint(),
                candidate.type(), List.of(option), List.of(), true);
        var withExample = new PlanQuestion(candidate.id(), candidate.question(), candidate.hint(),
                candidate.type(), candidate.options(), List.of(detail), true);
        String raw = fixture().path("rawPrompt").asText();
        assertThat(filter(List.of(withHint), raw)).containsExactly(withHint);
        assertThat(filter(List.of(withOption), raw)).containsExactly(withOption);
        assertThat(filter(List.of(withExample), raw)).containsExactly(withExample);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "候选方法可以说明适用条件，不预设必然显著。",
            "> 候选方法可以说明适用条件，不预设必然显著。",
            "引用：候选方法可以说明适用条件，不预设必然显著。",
            "```text\n候选方法可以说明适用条件，不预设必然显著。\n```",
            "其他研究：\n候选方法可以说明适用条件，不预设必然显著。",
            "如果以后批准，候选方法可以说明适用条件，不预设必然显著。",
            "候选方法可以说明适用条件，不预设必然显著。候选方法是否列出由我决定。"
    })
    void currentPermissionIsDistinctFromQuotesConditionsAndReservedChoices(String permission) throws Exception {
        String raw = "撰写研究设计的方法部分。具体统计检验依据未来数据分布和配对结构选择。\n" + permission;
        var candidate = scopeQuestion();
        boolean currentPermission = permission.equals("候选方法可以说明适用条件，不预设必然显著。");
        assertThat(filter(List.of(candidate), raw).isEmpty()).as(permission).isEqualTo(currentPermission);
    }

    @Test
    void restoredLengthPolicyKeepsTheHardBoundsAndUserStructure() throws Exception {
        String guidance = TaskBodyLengthContract.guidance(fixture().path("rawPrompt").asText());
        assertThat(guidance).contains("800–1200个汉字", "约1000个汉字", "原定标题、附表", "实际正文", "条件和未决参数")
                .doesNotContain("自报字数为");
        assertThat(TaskBodyLengthContract.guidance("撰写方法部分，正文800至1200个汉字，固定3段。"))
                .doesNotContain("个自然段");
        assertThat(TaskBodyLengthContract.guidance("只输出JSON，正文800至1200个汉字。" )).isEmpty();
        assertThat(TaskBodyLengthContract.guidance("翻译为英文：正文800至1200个汉字。" )).isEmpty();
        assertThat(TaskBodyLengthContract.guidance("撰写方法部分，正文800至1200个汉字或正文1500至2000个汉字。" )).isEmpty();
    }

    @Test
    void withdrawnGlobalStrategiesDoNotChangeEngineeringOrTranslationApplicability() {
        var registry = new PromptTemplateRegistryImpl();
        var code = registry.resolve(TemplateCode.AUTO, "使用requests库爬取网页标题。");
        assertThat(code.code()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        // 候选三十四已撤回强制方案化交付；核对当前实质代码契约，不恢复旧模板来满足旧措辞。
        assertThat(code.outputGuidance()).contains("可直接使用的代码", "必要运行说明", "不以方案文档代替")
                .doesNotContain("批处理、CLI");
        assertThat(code.acceptanceGuidance()).contains("正常、异常与边界场景", "保持现有功能兼容", "未实际运行的验证不得写成通过");
        var methods = TaskDeliveryProfile.ACADEMIC_METHODS;
        assertThat(methods.outputGuidance()).contains("拟议设计", "评分", "适用条件");
        assertThat(methods.acceptanceGuidance()).contains("已定与未决参数分开");
        assertThat(registry.resolve(TemplateCode.AUTO, "将下面内容翻译为英文，只输出译文。requests库可以获取标题。")
                .outputGuidance()).doesNotContain("URL含凭据", "可执行测试");
    }

    @Test
    void capturedScoringDeclarationRequiresAnchorsOnceWithoutInventingARange() throws Exception {
        JsonNode captured;
        try (var input = getClass().getResourceAsStream("/enhancement/candidate18-trace267.json")) {
            captured = JSON.readTree(input);
        }
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var section : captured.path("response").path("sections")) {
            var value = JSON.treeToValue(section, PromptSection.class);
            sections.put(value.type(), value);
        }
        var assessment = JSON.treeToValue(captured.path("response").path("assessment"), TaskEnhancementAssessment.class);
        var task = EffectiveTaskViewFactory.create(captured.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        String first = sections.get(PromptSectionType.TASK).content();
        assertThat(first).contains("各等级评分锚点", "可操作定义", "分歧处理规则").doesNotContain("1至5分", "阈值为");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(first);
    }

    /** 从原件重建全部候选，避免只用无选项的简化题冒称覆盖真实失败。 */
    private JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/enhancement/candidate19-trace265.json")) {
            return JSON.readTree(input);
        }
    }

    private PlanQuestion scopeQuestion() throws Exception {
        return JSON.treeToValue(fixture().path("questions").get(5), PlanQuestion.class);
    }

    private List<PlanQuestion> filter(List<PlanQuestion> questions, String raw) {
        return new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(raw, "", List.of()));
    }
}
