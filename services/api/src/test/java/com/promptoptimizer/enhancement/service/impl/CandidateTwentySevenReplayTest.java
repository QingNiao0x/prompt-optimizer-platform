package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 对329真实响应及独立作用域反例验证覆盖，不改原模型响应或原始题答。 */
class CandidateTwentySevenReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "Plan问答对提示词质量的影响";
    private static final String RAW = "为研究生撰写中文研究设计的方法部分，研究" + TOPIC + "。";
    private static final String ANSWER = "这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。";
    private static final String DELEGATION = "请根据原始研究目标提出可开展的推荐设计并说明适用条件";
    private static final String STATE = "研究主题为" + TOPIC + "，当前仅为拟定研究，尚未实施，也没有额外真实数据。";
    private static final String DIRECTIVE = "撰写中文研究设计的方法部分，围绕" + TOPIC + "提出可开展的推荐设计，并说明适用条件。";

    @Test
    void captured329AddsNoneOfTheThreeAlreadyExpressedRequirementsAndKeepsAllSources() throws Exception {
        Path evidence = repo().resolve("docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17");
        var trace = JSON.readTree(evidence.resolve("real/traces/329.json").toFile());
        var model = JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : model.path("sections")) {
            var section = JSON.treeToValue(node, PromptSection.class); sections.put(section.type(), section);
        }
        var input = JSON.readTree(evidence.resolve("real/Q05-NATURAL/input.json").toFile());
        List<PlanAnswer> answers = JSON.readerForListOf(PlanAnswer.class).readValue(input.path("planConfirmation").path("answers"));
        var task = EffectiveTaskViewFactory.create(input.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of(), false);
        var before = new EnumMap<>(sections); var originalView = task.modelInput();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections).isEqualTo(before);
        assertThat(task.modelInput()).isEqualTo(originalView);
        assertThat(task.modelInput().executionRequirements()).allSatisfy(requirement ->
                assertThat(requirement.sourceQuestionIds()).containsExactlyElementsOf(answers.stream().map(PlanAnswer::questionId).toList()));
        assertThat(task.pendingDecisions()).hasSize(7);
    }

    @Test
    void captured329AlsoPassesThroughTheCompleteAssemblerWithoutThoseThreeAdditions() throws Exception {
        Path evidence = repo().resolve("docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17");
        var trace = JSON.readTree(evidence.resolve("real/traces/329.json").toFile());
        var model = JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        var input = JSON.readTree(evidence.resolve("real/Q05-NATURAL/input.json").toFile());
        String raw = input.path("rawPrompt").asText();
        List<PlanAnswer> answers = JSON.readerForListOf(PlanAnswer.class).readValue(input.path("planConfirmation").path("answers"));
        var response = new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(model.path("sections")),
                "offline-replay-329", "deepseek-flash", false,
                JSON.readerForListOf(String.class).readValue(model.path("ambiguities")), List.of(),
                JSON.treeToValue(model.path("assessment"), TaskEnhancementAssessment.class));
        var empty = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
        var result = new OptimizationResultAssembler().assemble(response, empty,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                new ConstraintCompleterImpl().completeForTask(empty, PermissionPolicyInput.empty(), true,
                        TaskIntentResolver.resolve(TemplateCode.AUTO, raw)), false, 0, raw, List.of(), List.of(), task);
        assertThat(result.optimizedPrompt()).doesNotContain("\n- 这是尚未实施的研究。", "\n- 我没有额外真实数据",
                "\n- " + DELEGATION);
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.BACKGROUND)
                .singleElement().extracting(PromptSection::content).isEqualTo(response.sections().getFirst().content());
        assertThat(task.modelInput().executionRequirements()).allSatisfy(requirement ->
                assertThat(requirement.sourceQuestionIds()).containsExactlyElementsOf(answers.stream().map(PlanAnswer::questionId).toList()));
        Path output = Path.of("target/candidate27-trace329-assembled.json");
        Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        STATE, "本研究主题为" + TOPIC + "；当前仅为拟定研究，尚未实施，也没有额外真实数据。",
        "研究主题为" + TOPIC + ",目前只是拟定研究,尚未实施,也没有额外真实数据。"
    })
    void completeCurrentStateDoesNotAppendAnotherStatusOrDataDeclaration(String background) {
        var sections = sections(background, DIRECTIVE);
        PlanDecisionIntegrator.integrate(sections, task(ANSWER));
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo(background);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        DIRECTIVE, "撰写研究设计的方法部分，围绕" + TOPIC + "这一主题，提出可开展的推荐设计并说明适用条件。",
        "- " + DIRECTIVE
    })
    void completeSameTopicDirectiveDoesNotNeedTheLiteralDelegationSentence(String directive) {
        var sections = sections("背景", directive);
        PlanDecisionIntegrator.integrate(sections, task(ANSWER));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(directive);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "另一研究：\n" + STATE, "> " + STATE, "    " + STATE,
        "```text\n" + STATE + "\n```", "如果以后批准，" + STATE,
        "研究主题为睡眠质量，当前仅为拟定研究，尚未实施，也没有额外真实数据。",
        STATE + "但仅限甲院2025年样本。", "本研究已实施，也没有额外真实数据。",
        "研究主题为" + TOPIC + "，当前仅为拟定研究，尚未实施，但有额外真实数据。",
        STATE + "\n但上述仅适用于甲院2025年。",
        "研究主题为睡眠质量；当前仅为拟定研究，尚未实施，也没有额外真实数据。",
        "研究主题为" + TOPIC + "；研究主题为睡眠质量；当前仅为拟定研究，尚未实施，也没有额外真实数据。",
        "研究主题为睡眠质量。\n当前仅为拟定研究，尚未实施，也没有额外真实数据。"
    })
    void otherObjectsConditionsNegationsAndQuotedStatesDoNotSuppressTheOriginalFacts(String background) {
        var sections = sections(background, "写方法正文。");
        PlanDecisionIntegrator.integrate(sections, task(ANSWER));
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).contains("\n- 我没有额外真实数据");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "撰写中文研究设计的方法部分，围绕睡眠质量提出可开展的推荐设计，并说明适用条件。",
        "如果以后批准，" + DIRECTIVE, "另一研究：\n" + DIRECTIVE, "> " + DIRECTIVE,
        "    " + DIRECTIVE, DIRECTIVE + "但仅限甲院2025年样本。", DIRECTIVE + "\n但仅限甲院2025年样本。",
        "撰写中文研究设计的方法部分，围绕" + TOPIC + "提出可开展的推荐设计，但以后再说明适用条件。"
    })
    void anUnrelatedTopicOrLimitedDirectiveCannotCoverTheFullCurrentDelegation(String directive) {
        var sections = sections("背景", directive);
        PlanDecisionIntegrator.integrate(sections, task(ANSWER));
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("\n- " + DELEGATION);
    }

    @Test
    void partialScoringAndFuturePermissionRetainTheirOwnUnresolvedObjects() {
        for (String answer : List.of("只委托评分设计，分类由我决定。", "如果以后批准，再提出拟议设计。")) {
            var task = task(answer); var before = task.modelInput();
            var sections = sections(STATE, DIRECTIVE); PlanDecisionIntegrator.integrate(sections, task);
            assertThat(task.modelInput()).isEqualTo(before);
            assertThat(task.modelInput().pendingDecisions()).singleElement().satisfies(pending -> {
                assertThat(pending.designDelegated()).isFalse();
                assertThat(pending.question()).isEqualTo("4 类任务具体指哪四类？");
            });
        }
    }

    /** 每个反例绑定实际类别问题，局部指令不能被另一主题升级为整体委托。 */
    private EffectiveTaskView task(String answer) {
        return EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO,
                List.of(new PlanAnswer("category", "4 类任务具体指哪四类？", answer)), List.of(), false);
    }

    /** 使用四个正常责任段，覆盖判断走生产组装方法而非测试专用去重器。 */
    private EnumMap<PromptSectionType, PromptSection> sections(String background, String directive) {
        var values = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        values.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", background));
        values.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", directive));
        values.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "中文方法正文。"));
        values.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不确认任何既有参数。"));
        return values;
    }

    /** 同时适配模块Maven和隔离构建工作目录，不读取仓库外同名夹具。 */
    private static Path repo() {
        Path value = Path.of("").toAbsolutePath();
        while (value != null && !Files.isDirectory(value.resolve(".git"))) value = value.getParent();
        if (value == null) throw new IllegalStateException("Repository not found");
        return value;
    }
}
