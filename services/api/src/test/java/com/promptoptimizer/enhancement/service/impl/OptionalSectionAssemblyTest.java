package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四要素空槽只影响最终呈现，不取消任务、绑定事实、未决状态及平台规则的保护。
 * 同时保留旧四段输入，避免把协议简化误用为历史改写或内容丢弃。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OptionalSectionAssemblyTest {
    private final ContextSnapshot emptyContext = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "EMPTY");

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void emptySupportingSlotsDoNotBecomeFillerOrEmptyHeadings(PromptFormat format) {
        String raw = "解释二分查找的适用条件。";
        var result = assemble(raw, slots(raw), format);

        assertThat(result.sections()).extracting(PromptSection::type)
                .containsExactlyInAnyOrder(PromptSectionType.TASK, PromptSectionType.CONSTRAINTS);
        assertThat(result.sections()).allSatisfy(section -> {
            assertThat(section.title()).isNotBlank();
            assertThat(section.content()).isNotBlank();
        });
        assertThat(result.optimizedPrompt()).contains(raw)
                .doesNotContain("## 背景", "## 输出", "用户希望", "未提供背景");
        assertThat(result.appliedConstraints()).isNotEmpty();
        assertThat(result.appliedConstraints()).allSatisfy(rule ->
                assertThat(result.optimizedPrompt()).contains(rule));
        if (format == PromptFormat.COMPACT) assertThat(result.optimizedPrompt()).doesNotContain("## ");
        else assertThat(result.optimizedPrompt()).contains("## 任务", "## 约束");
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void explicitPromptDeliverableNeedsNoRepeatedOutputSlot(PromptFormat format) {
        String raw = "编写一份用于撰写会议纪要的提示词。";
        var result = assemble(raw, slots(raw), format);

        assertThat(result.optimizedPrompt()).contains(raw);
        assertThat(result.sections()).noneMatch(section -> section.type() == PromptSectionType.OUTPUT);
        assertThatThrownBy(() -> assemble(raw, slots("直接撰写会议纪要。"), format))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void absentModelBackgroundAndConstraintsStillReceiveBoundFactsAndUserRules() {
        String raw = "按方案实现订单审批。不得删除已有记录。";
        String evidence = "订单金额超过五万元时必须先由财务复核。";
        var fact = new PlanningFactCard("F01", PlanningFactCategory.BUSINESS_RULE,
                "docs/订单审批方案.txt", evidence);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet(fact.sourcePath(), "text", evidence, "订单审批规则", false)),
                List.of(), List.of(), "v1");

        var result = assemble(raw, slots("按方案实现订单审批。"), PromptFormat.COMPACT,
                context, List.of(fact), List.of(), true);

        assertThat(result.optimizedPrompt()).contains(evidence, fact.sourcePath(), "不得删除已有记录");
        assertThat(result.evidenceCards()).contains(fact);
        assertThat(result.appliedConstraints()).allSatisfy(rule ->
                assertThat(result.optimizedPrompt()).contains(rule));
        assertThat(result.sections()).allMatch(section -> !section.content().isBlank());
    }

    @Test
    void emptySupportingSlotsCannotEraseTheActualPendingPlanAnswer() {
        String raw = "整理预约取消通知要求。";
        var answers = List.of(new PlanAnswer("refund-date", "退款到账日期是什么时候？",
                "尚未确定，不得默认具体日期。"));

        var result = assemble(raw, slots(raw), PromptFormat.COMPACT,
                emptyContext, List.of(), answers, true);

        assertThat(result.optimizedPrompt()).contains("退款到账日期", "尚未确定", "不得默认具体日期");
        assertThat(result.sections()).allMatch(section -> !section.content().isBlank());
    }

    @Test
    void naturalBodyKeepsConfirmedStateAndDeliveryReferencesInTheirAssignedSlots() {
        String raw = "整理交接记录。";
        var answers = List.of(
                new PlanAnswer("existing-records", "当前有哪些交接记录？", "当前已有预算和采购清单的交接记录。"),
                new PlanAnswer("delivery-format", "输出格式是什么？", "交付带先后依赖的待办清单。"));
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw);
        var bundle = new ConstraintCompleterImpl().completeForTask(emptyContext, PermissionPolicyInput.empty(), true, task.intent());
        var request = new EnhancementProviderRequest(raw, emptyContext, template, List.of(), answers, true,
                bundle.visibleConstraints(), List.of(), EnhancementOptions.defaults(), null,
                List.of(), task.confirmedDecisions(), task);
        var references = BoundRequirementProjection.forProvider(request);
        assertThat(references).extracting(BoundRequirementProjection.Slot::destination)
                .contains(PromptSectionType.BACKGROUND, PromptSectionType.OUTPUT);
        assertThat(PromptRewriteStrategy.forTask(task, PromptFormat.COMPACT).instruction())
                .contains("BACKGROUND、OUTPUT与CONSTRAINTS仅在requirementReferences指定该destination时保留独立引用行",
                        "否则保留空字符串", "引用目的段与平台保护不变");

        // 自然正文集中在 TASK 不改变确认的责任段；辅助槽仅承接其本身绑定的来源引用。
        var sections = slots("依据已有交接记录整理待办，并保留每项的先后依赖。").stream().map(section -> {
            String refs = references.stream().filter(reference -> reference.destination() == section.type())
                    .map(BoundRequirementProjection.Slot::reference).collect(java.util.stream.Collectors.joining("\n"));
            return new PromptSection(section.type(), section.title(),
                    section.content().isEmpty() ? refs : section.content() + (refs.isEmpty() ? "" : "\n" + refs));
        }).toList();
        var result = assemble(raw, sections, PromptFormat.COMPACT, emptyContext, List.of(), answers, true);

        for (var reference : references) {
            assertThat(result.sections()).filteredOn(section -> section.type() == reference.destination())
                    .singleElement().satisfies(section -> assertThat(section.content()).contains(reference.statement()));
            assertThat(org.springframework.util.StringUtils.countOccurrencesOf(result.optimizedPrompt(), reference.statement()))
                    .as("source rendered once: %s", reference.destination()).isEqualTo(1);
        }
        assertThat(result.optimizedPrompt()).doesNotContain("{{REQ_", "## 背景", "## 输出");
        assertThat(result.constraintGroups()).isEqualTo(bundle);
        assertThat(task.confirmedDecisions()).hasSize(2);
    }

    @Test
    void blankTaskAndNullContentRemainInvalidEvenWhenTheOtherSlotsExist() {
        String raw = "解释二分查找。";
        assertThatThrownBy(() -> assemble(raw, slots("  \n"), PromptFormat.COMPACT))
                .isInstanceOf(ProviderResponseValidationException.class);
        for (PromptSectionType type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) {
            var sections = slots(raw).stream().map(section -> section.type() == type
                    ? new PromptSection(type, section.title(), null) : section).toList();
            assertThatThrownBy(() -> assemble(raw, sections, PromptFormat.COMPACT))
                    .as("null content for %s", type).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void missingOrDuplicateSupportingSlotsRemainInvalid() {
        String raw = "解释二分查找。";
        var missing = slots(raw).stream().filter(section -> section.type() != PromptSectionType.OUTPUT).toList();
        var duplicate = new ArrayList<>(slots(raw));
        duplicate.add(new PromptSection(PromptSectionType.OUTPUT, "输出", ""));

        assertThatThrownBy(() -> assemble(raw, missing, PromptFormat.COMPACT))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble(raw, duplicate, PromptFormat.COMPACT))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void fewerVisibleSectionsDoNotBypassCredentialChecks() {
        // 仅合成值，验证入口检测；不读取本机密钥或业务数据。
        assertThatThrownBy(() -> assemble("解释二分查找。",
                slots("解释二分查找。password=" + "safety-fixture-value"), PromptFormat.COMPACT))
                .isInstanceOf(ProviderResponseValidationException.class)
                .satisfies(error -> assertThat(((ProviderResponseValidationException) error).getReason())
                        .isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void independentLegacyFourSectionContentsRemainVisible(PromptFormat format) {
        String raw = "面向已有算法基础的读者解释二分查找，比较迭代写法与递归写法，不讨论其他搜索算法。";
        var sections = List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "读者已有算法基础。"),
                new PromptSection(PromptSectionType.TASK, "任务", "解释二分查找。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "比较迭代写法与递归写法。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不讨论其他搜索算法。"));

        var result = assemble(raw, sections, format);

        assertThat(result.sections()).extracting(PromptSection::type).contains(
                PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS);
        assertThat(result.optimizedPrompt()).contains("读者已有算法基础", "解释二分查找",
                "比较迭代写法与递归写法", "不讨论其他搜索算法");
    }

    /** 空槽仍有类型和标题；仅独立支持信息缺失，不省去内部覆盖校验的责任位置。 */
    private List<PromptSection> slots(String task) {
        return List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", ""),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", ""),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", ""));
    }

    private OptimizationResult assemble(String raw, List<PromptSection> sections, PromptFormat format) {
        return assemble(raw, sections, format, emptyContext, List.of(), List.of(), false);
    }

    /** 复用生产任务视图、动态规则及完整组装，检查最终正文而非只测试格式解析器。 */
    private OptimizationResult assemble(String raw, List<PromptSection> sections, PromptFormat format,
            ContextSnapshot context, List<PlanningFactCard> facts, List<PlanAnswer> answers, boolean confirmed) {
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, facts, false);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, task.intent());
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,
                List.of(), TaskEnhancementAssessment.Presentation.valueOf(format.name()));
        var response = new EnhancementProviderResponse(sections, "fixture", "fixture", true,
                List.of(), List.of(), assessment);
        return new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, confirmed,
                bundle, false, 1, raw, facts, List.of(), task, format);
    }
}
