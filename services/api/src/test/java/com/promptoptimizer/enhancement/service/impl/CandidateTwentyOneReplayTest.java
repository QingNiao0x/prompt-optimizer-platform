package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.EnumMap;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 复用十九实际原件，只核对当前平台投影，不伪造新的模型响应。 */
class CandidateTwentyOneReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextSnapshot EMPTY =
            new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");

    @Test
    void capturedFullAssemblyCorrectsDelegationLabelAndKeepsScoringAndLength() throws Exception {
        var fixture = fixture(283);
        String before = fixture.path("response").toString();
        var result = assemble(fixture, response(fixture, "deepseek-flash"));
        assertThat(result.optimizedPrompt()).doesNotContain("用户已明确保留的设计参数包括")
                .contains("实际采用参数尚未确认", "各等级评分锚点", "800", "1200");
        assertThat(result.sections().stream().anyMatch(section -> section.content().contains("各等级评分锚点"))).isTrue();
        assertThat(fixture.path("response").toString()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分量尺由我决定。", "评分量尺以后确认。", "仅限评分可推荐。", "评分量尺为1至7分。"})
    void mixedOrFutureAuthorityCannotRelabelWholeCapturedList(String boundary) throws Exception {
        var fixture = fixture(283);
        var sections = sections(fixture);
        String before = sections.get(PromptSectionType.BACKGROUND).content();
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText() + boundary,
                TemplateCode.AUTO, List.of(), List.of());
        PlanDecisionIntegrator.reconcileDelegatedStatus(sections, task);
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo(before);
    }

    @Test
    void newObjectIsNotBorrowedFromExistingDelegations() throws Exception {
        var fixture = fixture(283);
        var sections = sections(fixture);
        var background = sections.get(PromptSectionType.BACKGROUND);
        sections.put(background.type(), new PromptSection(background.type(), background.title(),
                background.content().replace("这些均未确认", "另一研究的评分量尺；这些均未确认")));
        String before = sections.get(PromptSectionType.BACKGROUND).content();
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of());
        PlanDecisionIntegrator.reconcileDelegatedStatus(sections, task);
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo(before);
    }

    @Test
    void finalMetadataCannotBypassSectionCredentialChecks() throws Exception {
        var fixture = fixture(283);
        assertThatThrownBy(() -> assemble(fixture, response(fixture, "token=CanaryValue98765")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
    }

    @Test
    void professionalWarningsStayOutsideCopiedPromptAndFalseCertificationIsRejected() {
        var task = EffectiveTaskViewFactory.create("分析心脑血管疾病死亡率和YLL，拟定研究，尚未实施。", TemplateCode.AUTO,
                List.of(), List.of());
        assertThat(ProfessionalReviewBoundary.warnings(task)).singleElement().asString().contains("尚未复核");
        assertThatThrownBy(() -> ProfessionalReviewBoundary.validate("本结果已通过专家复核。", task))
                .isInstanceOf(ProviderResponseValidationException.class);
        ProfessionalReviewBoundary.validate("不得宣称本结果已通过专家复核。", task);
        ProfessionalReviewBoundary.validate("“本结果已通过专家复核。”是待核实的引用。", task);
        var translation = EffectiveTaskViewFactory.create("只输出英文译文：本结果已通过专家复核。", TemplateCode.AUTO,
                List.of(), List.of());
        ProfessionalReviewBoundary.validate("本结果已通过专家复核。", translation);
    }

    /** 固定来源字节不变；测试只读取原件。 */
    private JsonNode fixture(int trace) throws Exception {
        try (var input = getClass().getResourceAsStream("/enhancement/candidate20-trace" + trace + ".json")) {
            return JSON.readTree(input);
        }
    }

    /** 沿用研究内部呈现归一化，不补设计参数或修改原件。 */
    private EnhancementProviderResponse response(JsonNode fixture, String model) throws Exception {
        var value = fixture.path("response");
        var assessment = value.path("assessment").deepCopy();
        if (!assessment.has("presentation")) ((ObjectNode) assessment).put("presentation", "STRUCTURED");
        return new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(value.path("sections")),
                "offline-replay", model, false, JSON.readerForListOf(String.class).readValue(value.path("ambiguities")),
                List.of(), JSON.treeToValue(assessment, TaskEnhancementAssessment.class));
    }

    /** 走完整组装与约束补全，保持复制正文的实际责任段。 */
    private OptimizationResult assemble(JsonNode fixture, EnhancementProviderResponse response) {
        String raw = fixture.path("rawPrompt").asText();
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        var bundle = new ConstraintCompleterImpl().completeForTask(EMPTY, PermissionPolicyInput.empty(), true, intent);
        return new OptimizationResultAssembler().assemble(response, EMPTY,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), List.of(), false,
                bundle, 0, raw, List.of(), List.of());
    }

    /** 副本上的字段检查不改写夹具及来源。 */
    private EnumMap<PromptSectionType, PromptSection> sections(JsonNode fixture) throws Exception {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : fixture.path("response").path("sections")) {
            var section = JSON.treeToValue(node, PromptSection.class);
            result.put(section.type(), section);
        }
        return result;
    }
}
