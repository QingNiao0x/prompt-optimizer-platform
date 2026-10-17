package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模式驱动生成后，共享组装仍保留四要素、来源、有效答案及安全规则。固定原件用于隔离组装影响。 */
class StructuredPromptPreferenceTest {
    private static final ContextSnapshot CONTEXT = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "fixture");
    private static final String RAW = "整理现有项目说明，保留代码与资料中的独立条件。";

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void explicitFormatOrdersExistingSectionsWithoutDroppingRequirements(PromptFormat format) {
        var opposite = format == PromptFormat.COMPACT ? TaskEnhancementAssessment.Presentation.STRUCTURED
                : TaskEnhancementAssessment.Presentation.COMPACT;
        var response = response(opposite, false);
        var automatic = assemble(RAW, response, null);
        var result = assemble(RAW, response, format);
        assertThat(result.promptFormat()).isEqualTo(format);
        assertThat(result.sections()).containsExactlyInAnyOrderElementsOf(automatic.sections());
        assertThat(result.ambiguities()).isEqualTo(automatic.ambiguities());
        assertThat(result.appliedConstraints()).isEqualTo(automatic.appliedConstraints());
        assertThat(result.evidenceCards()).isEqualTo(automatic.evidenceCards());
        assertThat(result.optimizedPrompt()).contains("甲机构", "乙机构", "以后确认", "不适用", "```java", "> 原始资料");
        if (format == PromptFormat.COMPACT) assertThat(result.optimizedPrompt()).doesNotContain("## 背景", "## 任务");
        else assertThat(result.optimizedPrompt()).contains("## 背景", "## 任务");
    }

    @Test
    void explicitStructuredTranslationKeepsPureTranslationRequirement() {
        var raw = "翻译为英文，只输出译文：会议取消。";
        var response = response(TaskEnhancementAssessment.Presentation.COMPACT, false);
        var automatic = assemble(raw, response, null);
        var structured = assemble(raw, response, PromptFormat.STRUCTURED);
        assertThat(automatic.promptFormat()).isEqualTo(PromptFormat.COMPACT);
        assertThat(structured.promptFormat()).isEqualTo(PromptFormat.STRUCTURED);
        assertThat(structured.optimizedPrompt()).contains("## 背景", "只输出译文");
        assertThat(structured.sections()).isEqualTo(automatic.sections());
        assertThat(structured.sections()).extracting(PromptSection::type).doesNotContain(PromptSectionType.ACCEPTANCE);
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void legacyAssessmentDoesNotChangeAcceptanceWhenPresentationChanges(PromptFormat format) {
        var legacy = response(null, false);
        var automatic = assemble(RAW, legacy, null);
        var requested = assemble(RAW, legacy, format);
        assertThat(requested.sections()).containsExactlyInAnyOrderElementsOf(automatic.sections());
        assertThat(requested.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(PromptSectionType.ACCEPTANCE);
            assertThat(section.content()).isEqualTo("原兼容验收规则。");
        });
    }

    @Test
    void naturalModeUsesTheSelectedFrameworkSequenceRatherThanRemovingHeadingsOnly() {
        String raw = "写一封简短邮件，请同事发送报告，不增加截止时间。";
        var response = response(TaskEnhancementAssessment.Presentation.STRUCTURED, false);
        var natural = assemble(raw, response, PromptFormat.COMPACT);
        var structured = assemble(raw, response, PromptFormat.STRUCTURED);
        assertThat(natural.sections().getFirst().type()).isEqualTo(PromptSectionType.TASK);
        assertThat(structured.sections().getFirst().type()).isEqualTo(PromptSectionType.BACKGROUND);
        assertThat(natural.optimizedPrompt()).startsWith(natural.sections().getFirst().content());
        assertThat(natural.sections()).containsExactlyInAnyOrderElementsOf(structured.sections());
        assertThat(natural.optimizedPrompt()).contains("以后确认", "不适用", "甲机构", "乙机构");
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void neitherModeBypassesRequiredCoverageOrSensitiveValueRejection(PromptFormat format) {
        var safe = response(TaskEnhancementAssessment.Presentation.COMPACT, false);
        var sensitive = new java.util.ArrayList<>(safe.sections());
        sensitive.set(1, new PromptSection(PromptSectionType.TASK, "任务", "password=" + "synthetic-secret-value"));
        for (var sections : List.of(safe.sections().subList(1, safe.sections().size()), sensitive)) {
            var invalid = new EnhancementProviderResponse(sections, "offline", "fixture", true,
                    List.of(), List.of(), safe.assessment());
            assertThatThrownBy(() -> assemble(RAW, invalid, format))
                    .isInstanceOfSatisfying(com.promptoptimizer.provider.domain.ProviderException.class, error -> {
                        assertThat(error.getFailureType()).isEqualTo(
                                com.promptoptimizer.provider.domain.ProviderFailureType.INVALID_RESPONSE);
                        assertThat(error.getMessage()).doesNotContain("synthetic-secret-value");
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void explicitlyProvidedAcceptanceIsRetainedInBothFormats(PromptFormat format) {
        var result = assemble(RAW, response(TaskEnhancementAssessment.Presentation.COMPACT, true), format);
        assertThat(result.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(PromptSectionType.ACCEPTANCE);
            assertThat(section.content()).isEqualTo("分别检查甲机构与乙机构的适用条件。");
        });
        assertThat(result.optimizedPrompt()).contains("分别检查甲机构与乙机构的适用条件。");
    }

    @Test
    void absentOptionsRemainAutomaticAndIllegalEnumIsRejected() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(EnhancementOptions.defaults().promptFormat()).isNull();
        assertThat(new EnhancementOptions(TemplateCode.AUTO, false, true, false).promptFormat()).isNull();
        assertThat(mapper.readValue("{}", EnhancementOptions.class).promptFormat()).isNull();
        assertThat(mapper.readValue("{\"promptFormat\":\"COMPACT\"}", EnhancementOptions.class).promptFormat())
                .isEqualTo(PromptFormat.COMPACT);
        assertThatThrownBy(() -> mapper.readValue("{\"promptFormat\":\"UNKNOWN\"}", EnhancementOptions.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void directMockEnhancementUsesTheExplicitPreference(PromptFormat format) {
        var orchestrator = new DefaultEnhancementOrchestrator(input -> CONTEXT, new AmbiguityDetector(),
                new PromptTemplateRegistryImpl(), new ConstraintCompleterImpl(), new MockPromptEnhancementProvider(),
                TestActors.currentActor(), Clock.systemUTC());
        var result = orchestrator.optimize(new OptimizationRequest("撰写一封简短邮件，请同事确认会议时间。", null,
                new EnhancementOptions(TemplateCode.AUTO, false, true, false, format), List.of(),
                PermissionPolicyInput.empty()));
        assertThat(result.promptFormat()).isEqualTo(format);
        assertThat(result.optimizedPrompt()).contains("会议时间");
        assertThat(result.provider().mock()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void mockOrchestratorHonorsFormatAndKeepsBoundPlanAnswer(PromptFormat format) {
        var clock = Clock.systemUTC();
        com.promptoptimizer.context.service.ContextAnalyzer analyzer = input -> CONTEXT;
        var filter = new ProtectedContextFilterImpl();
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), analyzer,
                filter, TestActors.currentActor(), clock);
        var raw = "为某地区撰写活动通知。";
        var question = new PlanQuestion("region", "本次活动在哪个地区举办？", "填写实际地区。",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var registration = sessions.registerPlan(raw, "", List.of(), sessions.resolveForPlan(null, raw, ""),
                List.of(question));
        var confirmation = new PlanConfirmation(registration.planId(), registration.planningContext(),
                List.of(new PlanAnswer(question.id(), question.question(), "天津市")));
        var orchestrator = new DefaultEnhancementOrchestrator(analyzer, new AmbiguityDetector(),
                new PromptTemplateRegistryImpl(), new ConstraintCompleterImpl(), new MockPromptEnhancementProvider(),
                new OptimizationResultAssembler(), filter, sessions, clock);
        var result = orchestrator.optimize(new OptimizationRequest(raw, null,
                new EnhancementOptions(TemplateCode.AUTO, false, true, false, format), List.of(),
                PermissionPolicyInput.empty(), confirmation));
        assertThat(result.promptFormat()).isEqualTo(format);
        assertThat(result.provider().mock()).isTrue();
        assertThat(result.optimizedPrompt()).contains("天津市", "活动通知");
        assertThat(result.ambiguities()).noneMatch(value -> value.contains(question.question()));
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void historySerializationKeepsActualRequestedFormatAndBody(PromptFormat format) throws Exception {
        var original = assemble(RAW, response(TaskEnhancementAssessment.Presentation.COMPACT, false), format);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var restored = mapper.readValue(mapper.writeValueAsString(original.withLatencyMs(19).withModelVersion("v1")),
                OptimizationResult.class);
        assertThat(restored.promptFormat()).isEqualTo(format);
        assertThat(restored.optimizedPrompt()).isEqualTo(original.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(original.sections());
    }

    private OptimizationResult assemble(String raw, EnhancementProviderResponse response, PromptFormat format) {
        var taskView = EffectiveTaskViewFactory.create(raw, TemplateCode.GENERAL, List.of(), List.of(), false);
        return new OptimizationResultAssembler().assemble(response, CONTEXT,
                new PromptTemplate(TemplateCode.GENERAL, "保留原定交付。", "原兼容验收规则。", "示例。"),
                List.of(), List.of(), false, ConstraintBundle.fromLegacy(List.of(), false), false, 1, raw,
                List.of(), List.of(), taskView, format);
    }

    private EnhancementProviderResponse response(TaskEnhancementAssessment.Presentation presentation, boolean acceptance) {
        var sections = new java.util.ArrayList<>(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "当前资料用于说明现有项目。"),
                new PromptSection(PromptSectionType.TASK, "任务", "按原始要求整理内容，保留原定交付。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "保留代码：\n```java\nvar total = 1;\n```\n> 原始资料：仅供引用。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "甲机构：仅2025年适用。\n乙机构：仅2024年适用。\n以后确认的条件尚未生效。\n例外：归档项目不适用。")));
        if (acceptance) sections.add(new PromptSection(PromptSectionType.ACCEPTANCE, "验收标准", "分别检查甲机构与乙机构的适用条件。"));
        var assessment = presentation == null ? null : new TaskEnhancementAssessment(
                TaskEnhancementAssessment.Sufficiency.SUFFICIENT, List.of(), presentation);
        return new EnhancementProviderResponse(sections, "offline", "fixture", true, List.of(), List.of(), assessment);
    }
}
