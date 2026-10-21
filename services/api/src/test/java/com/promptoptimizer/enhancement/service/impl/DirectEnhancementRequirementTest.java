package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证原任务不被误投影为约束，以及明确输出声明在模型遗漏、两种呈现和Mock下的保真。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DirectEnhancementRequirementTest {
    private static final String RAW = "请帮我查询全球使用AI最多的职业/行业";
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
    private final RequirementFidelityGuard guard = new RequirementFidelityGuard();

    @ParameterizedTest
    @ValueSource(strings = {RAW, "找出销量最多的产品。", "比较借阅次数最多的图书。", "查询获奖次数最多的作品。", "查询使用AI最多 的职业。"})
    void relativeSuperlativeIsATargetRatherThanAnUpperBound(String raw) {
        assertThat(guard.explicitRules(raw, List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"最多的请求次数为MAX_REQUESTS。", "每小时最多的请求次数为100。", "报告最多的字数是LIMIT。",
            "最多的预算金额=remainingBudget/2。", "最多的请求次数：MAX_REQUESTS。", "最多提交limit份报告。", "最多消耗剩余额度的一半。",
            "最多运行到用户指定的截止时间。", "找出销量最多的产品，但不得包含退货订单。"})
    void realUpperBoundsAndIndependentProhibitionsRemainComplete(String raw) {
        assertThat(guard.explicitRules(raw, List.of())).containsExactly(raw);
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void queryTargetIsNotRegisteredOrAppendedAsAConstraint(PromptFormat format) {
        var request = request(RAW);
        assertThat(BoundRequirementProjection.forProvider(request))
                .noneMatch(slot -> slot.statement().equals(RAW));
        var result = assemble(request, sections("查询全球使用AI较多的职业和行业，说明结果、依据及适用范围。", "每项结论标注来源与统计口径。"), format);
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .noneMatch(section -> section.content().contains(RAW));
        assertThat(result.optimizedPrompt()).contains("职业和行业", "来源与统计口径");
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void explicitOutputFieldsAreRestoredWhenTheModelOmitsThem(PromptFormat format) {
        String raw = RAW + "。\n输出要求：用排名表呈现，列出排名、职业或行业、AI应用场景、统计口径、数据来源和统计时间。";
        var request = request(raw);
        var result = assemble(request, sections("查询全球使用AI较多的职业和行业。", "给出查询结果。"), format);
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.OUTPUT)
                .singleElement().satisfies(section -> assertThat(section.content()).contains("排名表", "统计口径", "数据来源", "统计时间", "AI应用场景"));
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .noneMatch(section -> section.content().contains(RAW) || section.content().contains("用排名表呈现"));
    }

    @Test
    void mockRetainsExplicitFieldsAndDoesNotCreateAConstraintFromTheQuery() {
        String raw = RAW + "。\n输出要求：用排名表呈现，包含统计口径、数据来源和统计时间。";
        var request = request(raw);
        var response = new MockPromptEnhancementProvider().enhance(request);
        var result = assemble(request, response.sections(), PromptFormat.COMPACT);
        assertThat(result.optimizedPrompt()).contains("排名表", "统计口径", "数据来源", "统计时间");
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .noneMatch(section -> section.content().contains(RAW));
    }

    @Test
    void multilineOutputRulesAreDeliveredOnceInOutputInsteadOfBeingCopiedToConstraints() {
        String output = "输出要求：\n必须给出排名表。\n仅使用提供的资料，不能编造数据。";
        var request = request(RAW + "。\n" + output);
        var result = assemble(request, sections("查询相关职业和行业。", "给出查询结果。"), PromptFormat.COMPACT);
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .noneMatch(section -> section.content().contains("必须给出排名表") || section.content().contains("仅使用提供的资料"));
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(result.optimizedPrompt(), ExplicitOutputRequirements.bodyOf(output))).isEqualTo(1);
    }

    @Test
    void outputReferencesUseTheOutputDestinationAndRetainScopeValidation() {
        String declaration = "输出要求：用表格列出对象、来源和统计口径。";
        var request = request(RAW + "。\n" + declaration);
        var slots = BoundRequirementProjection.forProvider(request);
        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.destination()).isEqualTo(PromptSectionType.OUTPUT);
            assertThat(slot.statement()).isEqualTo(ExplicitOutputRequirements.bodyOf(declaration));
        });
        String reference = slots.getFirst().reference();
        var result = assemble(request, sections("查询相关职业和行业。", reference), PromptFormat.COMPACT);
        assertThat(result.optimizedPrompt()).contains(ExplicitOutputRequirements.bodyOf(declaration)).doesNotContain("{{REQ_");
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(result.optimizedPrompt(), ExplicitOutputRequirements.bodyOf(declaration))).isEqualTo(1);
        var misplaced = sections("查询相关职业和行业。", "查询结果。").stream().map(section ->
                section.type() == PromptSectionType.CONSTRAINTS ? new PromptSection(section.type(), section.title(), reference) : section).toList();
        assertThatThrownBy(() -> assemble(request, misplaced, PromptFormat.COMPACT))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble(request, sections("查询相关职业和行业。", "如果以后确认：\n" + reference), PromptFormat.COMPACT))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void real488DoesNotRepeatAnOutputStatementJustBecauseItsHeadingWasOmitted() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/fixtures/direct-output-488.json")) {
            var response = json.readTree(input);
            var modelSections = new java.util.ArrayList<PromptSection>();
            for (var section : response.path("sections")) modelSections.add(new PromptSection(
                    PromptSectionType.valueOf(section.path("type").asText()), section.path("title").asText(), section.path("content").asText()));
            String body = "用排名表呈现，列出排名、职业或行业、AI应用场景、统计口径、数据来源和统计时间。不同口径不能直接合并排名，无法可靠确定名次时说明依据与范围。";
            var result = assemble(request(RAW + "。\n输出要求：" + body), modelSections, PromptFormat.COMPACT);
            var output = result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT).findFirst().orElseThrow().content();
            assertThat(output).containsOnlyOnce("用排名表呈现");
            assertThat(output).contains(body);
        }
    }

    private EnhancementProviderRequest request(String raw) {
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
        return new EnhancementProviderRequest(raw, context, template, List.of(), List.of(), false,
                bundle.visibleConstraints(), List.of(), EnhancementOptions.defaults(), null, List.of(), List.of(), task);
    }

    private List<PromptSection> sections(String task, String output) {
        return List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", ""),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", ""));
    }

    /** 使用生产任务视图、规则补全及完整组装，避免仅断言文本辅助方法。 */
    private com.promptoptimizer.enhancement.domain.OptimizationResult assemble(EnhancementProviderRequest request,
            List<PromptSection> sections, PromptFormat format) {
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, request.taskView().intent());
        return new OptimizationResultAssembler().assemble(new EnhancementProviderResponse(sections, "fixture", "local", false, List.of()),
                context, request.template(), List.of(), List.of(), false, bundle, 1L, request.rawPrompt(),
                List.of(), List.of(), request.taskView(), format);
    }
}
