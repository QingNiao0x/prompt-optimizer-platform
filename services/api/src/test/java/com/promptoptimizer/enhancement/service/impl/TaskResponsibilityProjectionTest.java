package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * 416／418只读来源回放与新机制的合成响应验证；责任建议不授权、引用不证明近义句等价。
 * 这些用例参与本次开发，仅作回归，不能当作独立泛化或新的真实模型结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskResponsibilityProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String RAW = "用TypeScript实现文本去重。";
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");

    @Test
    void actual416UsesServerResponsibilityRegardlessOfModelAdvice() throws Exception {
        var questions = questions();
        var filter = new PlanQuestionFilter();
        assertThat(filter.filter(questions, input(RAW)))
                .containsExactly(questions.get(0), questions.get(1), questions.get(3));
        var advised = advise(questions.get(2), RAW);
        var candidates = List.of(questions.get(0), questions.get(1), advised, questions.get(3));
        assertThat(filter.filter(candidates, input(RAW)))
                .containsExactly(questions.get(0), questions.get(1), questions.get(3));
        assertThat(advised.options()).isEqualTo(questions.get(2).options());
        assertThat(mapper.writeValueAsString(advised)).doesNotContain("responsibility", "CODE_PACKAGING_ONLY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"如何组织最终实现？", "你更希望怎样使用这份代码？", "交付物应该是哪一种形态？", "实现的入口与调用方式由谁来组织？"})
    void responsibilityDoesNotDependOnQuestionWording(String wording) throws Exception {
        var original = questions().get(2);
        var changed = new PlanQuestion(original.id(), wording, original.hint(), original.type(), original.options(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(advise(changed, RAW)), input(RAW))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"由我决定函数还是脚本，请先询问。", "必须兼容现有接口。", "若用于生产，先确认部署方式。", "不得修改现有模块。", "仅提供函数，其他包装尚未确定。"})
    void explicitUserChoiceAndActualContractCannotBeOverriddenByAdvice(String suffix) throws Exception {
        String raw = RAW + suffix;
        var question = advise(questions().get(2), raw);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input(raw))).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"按排序顺序返回", "按整段判断重复", "逐字符比较", "输出CSV格式", "忽略大小写", "超时为30秒", "需要共享授权", "对接现有接口", "第三方依赖", "如果以后确认才使用", "返回网址", "读取另一对象并排除记录"})
    void anIndependentEffectInAnyOptionKeepsTheWholeQuestion(String effect) throws Exception {
        var original = questions().get(2);
        var options = new java.util.ArrayList<>(original.options());
        var first = options.getFirst();
        options.set(0, new PlanOption(first.id(), first.label(), effect, first.answer(), first.recommended(), first.recommendationReason()));
        var question = advise(new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), options, List.of(), true), RAW);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input(RAW))).containsExactly(question);
    }

    @Test
    void modelAdviceDoesNotControlResponsibilityAndActualEvidenceRemainsProtected() throws Exception {
        var original = questions().get(2);
        var incorrect = new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), original.options(), List.of(), true,
                new PlanQuestionResponsibility("EXECUTOR", "CODE_PACKAGING_ONLY", "另一个任务", original.question(), List.of("lib", "cli", "both")));
        assertThat(new PlanQuestionFilter().filter(List.of(incorrect), input(RAW))).isEmpty();
        assertThat(new PlanQuestionFilter().filter(List.of(incorrect), input(RAW + "交付方式由我决定。"))).containsExactly(incorrect);
        var advised = advise(original, RAW);
        assertThat(new PlanQuestionFilter().filter(List.of(advised), new PlanningProviderRequest(RAW, "既有交付契约需要核查", List.of())))
                .containsExactly(advised);
        var wrongIds = new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), original.options(), List.of(), true,
                new PlanQuestionResponsibility("EXECUTOR", "CODE_PACKAGING_ONLY", RAW, original.question(), List.of("lib", "cli")));
        assertThat(new PlanQuestionFilter().filter(List.of(wrongIds), input(RAW))).isEmpty();
    }

    @Test
    void old418StillUsesTheCompatibilityPathRatherThanPretendingItHasReferences() throws Exception {
        var response = response418();
        var task = task();
        var sections = sections(response.sections());
        var constraints = groups(task);
        assertThat(BoundRequirementProjection.expand(sections, task, constraints)).isEmpty();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("\n- 按整行完全相同去重。");
        // 原件未插入新引用；新机制的收益须由新响应／真实请求另行验证。
        assertThat(response.sections().get(1).content()).doesNotContain("{{REQ_");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameSourceIsRenderedOnceAndAllSafetyAndHistoryFieldsRemain(boolean structured) throws Exception {
        var task = task();
        var constraints = groups(task);
        var request = request(task, constraints.visibleConstraints());
        var slots = BoundRequirementProjection.forProvider(request);
        var generated = sections(response418().sections());
        for (var type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) {
            String lead = type == PromptSectionType.TASK ? "实现可直接使用的文本去重逻辑。" : type == PromptSectionType.OUTPUT
                    ? "交付实现及必要运行说明。" : "未实际运行的验证不得写成通过。";
            String references = slots.stream().filter(slot -> slot.destination() == type)
                    .map(BoundRequirementProjection.Slot::reference).collect(java.util.stream.Collectors.joining("\n"));
            generated.put(type, new PromptSection(type, generated.get(type).title(), lead + "\n" + references));
        }
        generated.remove(PromptSectionType.ACCEPTANCE);
        var response = new EnhancementProviderResponse(List.copyOf(generated.values()), "fixture", "fixture", true, List.of());
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.FEATURE_DEVELOPMENT, RAW);
        var result = new OptimizationResultAssembler().assemble(response, context, template, List.of(), answers(), true,
                constraints, false, 1, RAW, List.of(), List.of(), task,
                structured ? PromptFormat.STRUCTURED : PromptFormat.COMPACT);
        assertThat(result.optimizedPrompt()).doesNotContain("{{REQ_");
        for (var requirement : task.executionRequirements()) {
            assertThat(org.springframework.util.StringUtils.countOccurrencesOf(result.optimizedPrompt(), requirement.statement())).isEqualTo(1);
        }
        for (String rule : constraints.taskSpecific()) {
            assertThat(org.springframework.util.StringUtils.countOccurrencesOf(result.optimizedPrompt(), rule)).isEqualTo(1);
        }
        assertThat(result.appliedConstraints()).containsAll(constraints.visibleConstraints());
        assertThat(result.constraintGroups()).isEqualTo(constraints);
        assertThat(result.optimizedPrompt()).contains(constraints.platformMandatory().toArray(String[]::new));
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).path("optimizedPrompt").asText()).isEqualTo(result.optimizedPrompt());
        assertThat(task.confirmedDecisions()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后批准：\n%s", "示例：\n%s", "另一任务：\n%s", "引用：\n%s", "```text\n%s\n```", "    %s", "不得%s", "建议采用%s", "未知范围：\n%s"})
    void sourceReferencesCannotEscapeConditionsQuotationOrUnknownScope(String pattern) throws Exception {
        var task = task();
        var constraints = groups(task);
        var slot = BoundRequirementProjection.forProvider(request(task, constraints.visibleConstraints())).getFirst();
        var generated = sections(response418().sections());
        generated.put(slot.destination(), new PromptSection(slot.destination(), "任务", pattern.formatted(slot.reference())));
        assertThatThrownBy(() -> BoundRequirementProjection.expand(generated, task, constraints))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void duplicateReferenceDoesNotAuthorizeItsConditionalOccurrenceOrOtherSource() throws Exception {
        var task = task();
        var constraints = groups(task);
        var slot = BoundRequirementProjection.forProvider(request(task, constraints.visibleConstraints())).getFirst();
        var generated = sections(response418().sections());
        generated.put(slot.destination(), new PromptSection(slot.destination(), "任务", slot.reference() + "\n如果以后批准：\n" + slot.reference()));
        assertThatThrownBy(() -> BoundRequirementProjection.expand(generated, task, constraints)).isInstanceOf(ProviderResponseValidationException.class);
        generated.put(slot.destination(), new PromptSection(slot.destination(), "任务", "{{REQ_foreign_source}}"));
        assertThatThrownBy(() -> BoundRequirementProjection.expand(generated, task, constraints)).isInstanceOf(ProviderResponseValidationException.class);
        generated.put(slot.destination(), new PromptSection(slot.destination(), "任务", slot.reference() + "\n" + slot.reference()));
        var delivered = BoundRequirementProjection.expand(generated, task, constraints);
        PlanDecisionIntegrator.integrate(generated, task, delivered);
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(generated.get(slot.destination()).content(), slot.statement())).isEqualTo(1);
    }

    @Test
    void unresolvedParameterNeverBecomesAnExecutableConfirmedValue() {
        var task = EffectiveTaskViewFactory.create("撰写研究方案。", TemplateCode.AUTO,
                List.of(new PlanAnswer("population", "标准人口采用哪一年？", "暂不确定，不得默认任何年份。")), List.of(), false);
        assertThat(task.pendingDecisions()).hasSize(1);
        var slots = BoundRequirementProjection.forProvider(request(task, List.of()));
        assertThat(slots).allMatch(slot -> !slot.statement().contains("2010") && !slot.statement().contains("已确认"));
        assertThat(task.modelInput().pendingDecisions().getFirst().scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED);
    }

    @Test
    void theObservedTemperatureFailureIsNowRegressionAndItsBusinessChoicesRemain() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/candidate41-temperature-replay.json")) {
            var fixture = mapper.readTree(stream);
            String original = fixture.path("modelResponseRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(fixture.path("modelResponseSha256").asText());
            var parsed = mapper.convertValue(mapper.readTree(original).path("questions"), new TypeReference<List<PlanQuestion>>() { });
            assertThat(new PlanQuestionFilter().filter(parsed, input(fixture.path("rawPrompt").asText())))
                    .extracting(PlanQuestion::id).containsExactly("supported_units", "precision_rule");
            // 原件确实将普通包装标为用户决定；内部兼容字段不参与公共JSON反序列化或服务端责任判定。
            assertThat(mapper.readTree(original).path("questions").get(1).path("responsibility").path("owner").asText())
                    .isEqualTo("USER");
        }
    }

    @Test
    void differentKnownTechnicalValuesRemainIndependentEvenWithPackagingLabels() {
        var question = new PlanQuestion("shape", "应如何交付实现？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("csv", "函数", "", "返回CSV结果的函数", false, ""),
                        new PlanOption("json", "命令行", "", "返回JSON结果的命令行", false, "")), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input("用Java实现CSV或JSON转换工具。"))).containsExactly(question);
    }

    private com.promptoptimizer.policy.domain.ConstraintBundle groups(EffectiveTaskView task) {
        return new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
    }

    private EnhancementProviderRequest request(EffectiveTaskView task, List<String> constraints) {
        var template = new PromptTemplateRegistryImpl().resolve(task.intent().templateCode(), task.originalPrompt());
        return new EnhancementProviderRequest(task.originalPrompt(), context, template, List.of(), answersUnchecked(), true,
                constraints, List.of(), EnhancementOptions.defaults(), null, List.of(), task.confirmedDecisions(), task);
    }

    private List<PlanAnswer> answersUnchecked() {
        try { return answers(); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private EffectiveTaskView task() throws Exception {
        return EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO, answers(), List.of(), false);
    }

    private List<PlanAnswer> answers() throws Exception {
        return mapper.convertValue(fixture().path("answers"), new TypeReference<List<PlanAnswer>>() { });
    }

    private List<PlanQuestion> questions() throws Exception {
        return mapper.convertValue(model(416).path("questions"), new TypeReference<List<PlanQuestion>>() { });
    }

    private EnhancementProviderResponse response418() throws Exception {
        return new EnhancementProviderResponse(mapper.convertValue(model(418).path("sections"), new TypeReference<List<PromptSection>>() { }),
                "fixture", "fixture", true, List.of());
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/candidate41-source-replay.json")) { return mapper.readTree(stream); }
    }

    private com.fasterxml.jackson.databind.JsonNode model(int trace) throws Exception {
        var sample = java.util.stream.StreamSupport.stream(fixture().path("samples").spliterator(), false)
                .filter(item -> item.path("trace").asInt() == trace).findFirst().orElseThrow();
        String raw = sample.path("modelResponseRaw").asText();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(sample.path("modelResponseSha256").asText());
        return mapper.readTree(raw);
    }

    private EnumMap<PromptSectionType, PromptSection> sections(List<PromptSection> values) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        values.forEach(section -> result.put(section.type(), section));
        return result;
    }

    private PlanningProviderRequest input(String raw) { return new PlanningProviderRequest(raw, "", List.of()); }

    private PlanQuestion advise(PlanQuestion original, String raw) {
        return new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), original.options(), original.examples(),
                original.allowCustomAnswer(), new PlanQuestionResponsibility("EXECUTOR", "CODE_PACKAGING_ONLY", raw,
                original.question(), original.options().stream().map(PlanOption::id).toList()));
    }
}
