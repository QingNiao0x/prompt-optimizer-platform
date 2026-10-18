package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.EnumMap;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 原文规则与Plan来源共用呈现记录；限定引用与逐字证据，不宣称任意改写语义等价。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class SourceOwnershipProjectionTest {
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");

    @ParameterizedTest
    @ValueSource(strings = {"不得添加示例。", "仅当用户批准时才修改原文件；否则只交付代码。", "禁止输出密码；异常信息也不得包含密码。"})
    void originalRulesReceiveServerOwnedSlotsAndKeepTheirFullScope(String rule) {
        String raw = "用Python实现日志统计。" + rule;
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
        var request = new EnhancementProviderRequest(raw, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), List.of(), false,
                bundle.visibleConstraints(), List.of(), EnhancementOptions.defaults(), null, List.of(), List.of(), task);
        var rules = new RequirementFidelityGuard().explicitRules(task.effectivePrompt(), task.confirmedDecisions());
        var slots = BoundRequirementProjection.forProvider(request);
        assertThat(rules).isNotEmpty();
        assertThat(slots).extracting(BoundRequirementProjection.Slot::statement).containsAll(rules);
        assertThat(slots).noneMatch(slot -> bundle.platformMandatory().contains(slot.statement()));
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) {
            String references = slots.stream().filter(slot -> slot.destination() == type)
                    .map(BoundRequirementProjection.Slot::reference).collect(java.util.stream.Collectors.joining("\n"));
            sections.put(type, new PromptSection(type, type.name(), "当前执行要求。\n" + references));
        }
        var projection = BoundRequirementProjection.expandTracked(sections, task, bundle);
        assertThat(projection.coveredRules(sections)).containsAll(rules);
        // 后续步骤若移除了表达，旧记录不能继续证明正文已经包含该规则。
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "其他独立要求。"));
        assertThat(projection.coveredRules(sections)).isEmpty();
    }

    @Test
    void assertionBoundariesAreGrammaticalAndDoNotDependOnTheTaskDomain() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束",
                "校验所有外部输入，明确处理空值、非法值和边界条件。\n为核心逻辑补充正常、异常和边界场景测试；未实际运行的验证不得写成通过。"));
        assertThat(TaskConstraintCoverage.uncovered(sections, List.of(
                "校验所有外部输入，并明确处理空值、非法值和边界条件。", "为核心逻辑补充正常、异常和边界场景测试。"))).isEmpty();
        assertThat(TaskConstraintCoverage.uncovered(sections, List.of("不得处理空值、非法值和边界条件。",
                "为另一模块补充正常、异常和边界场景测试。"))).hasSize(2);
    }

    @Test
    void explicitRecipientIsNotTheCommissioningParty() {
        String raw = "为青禾工作坊撰写预约取消通知邮件，收件人为已付款报名者。";
        var anchors = TaskMeaningContract.from(raw, List.of()).anchors();
        assertThat(anchors.stream().filter(anchor -> anchor.role() == TaskMeaningContract.Role.AUDIENCE))
                .extracting(TaskMeaningContract.Anchor::text).containsExactly("已付款报名者");
        assertThat(anchors).anyMatch(anchor -> anchor.text().equals("青禾工作坊") && anchor.role().name().equals("BENEFICIARY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"为社区服务站撰写通知，面向居民。", "为学生会写一封邮件，目标读者为全体会员。", "为实验室编写指南，受众是初学者。"})
    void recipientBindingUsesSyntacticRolesAcrossDifferentTasks(String raw) {
        var anchors = TaskMeaningContract.from(raw, List.of()).anchors();
        assertThat(anchors.stream().filter(anchor -> anchor.role() == TaskMeaningContract.Role.AUDIENCE)).hasSize(1);
        assertThat(anchors.stream().filter(anchor -> anchor.role().name().equals("BENEFICIARY"))).hasSize(1);
    }

    @Test
    void audienceAndResearchSubjectStillHaveIndependentEvidence() {
        var contract = TaskMeaningContract.from("为研究生撰写研究方案，研究对象为工程师。", List.of());
        assertThat(contract.anchors()).anyMatch(anchor -> anchor.role() == TaskMeaningContract.Role.AUDIENCE && anchor.text().equals("研究生"));
        assertThatThrownBy(() -> contract.validate("研究对象为研究生。", "background"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void directedWritingDoesNotMakeTheWritingActionPartOfTheRecipient() {
        assertThat(TaskMeaningContract.from("面向研究生撰写研究方法，研究对象为工程师。", List.of()).anchors()
                .stream().filter(anchor -> anchor.role() == TaskMeaningContract.Role.AUDIENCE))
                .extracting(TaskMeaningContract.Anchor::text).containsExactly("研究生");
    }

    @ParameterizedTest
    @ValueSource(strings = {"例如：收件人为董事会。", "以后收件人为董事会。", "引用：\"收件人为董事会\"。", "\n```text\n收件人为董事会\n```", "\n> 收件人为董事会。"})
    void nonCurrentRecipientDoesNotOverrideTheCurrentAudience(String suffix) {
        var anchors = TaskMeaningContract.from("为研究生撰写方法说明。" + suffix, List.of()).anchors();
        assertThat(anchors.stream().filter(anchor -> anchor.role() == TaskMeaningContract.Role.AUDIENCE))
                .extracting(TaskMeaningContract.Anchor::text).containsExactly("研究生");
    }

    @Test
    void unresolvedSourcesAreSeparateFromConfirmedRulesAndRemainInTheAuthorityList() {
        var answers = List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("population", "标准人口采用哪一年？",
                "暂不确定，不得默认任何年份；请提出拟议方案并说明条件。"));
        String raw = "撰写死亡率分析的研究方案。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
        var request = new EnhancementProviderRequest(raw, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                bundle.visibleConstraints(), List.of(), EnhancementOptions.defaults(), null, List.of(), task.confirmedDecisions(), task);
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        var slots = BoundRequirementProjection.forProvider(request);
        for (var type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) {
            sections.put(type, new PromptSection(type, type.name(), slots.stream().filter(slot -> slot.destination() == type)
                    .map(BoundRequirementProjection.Slot::reference).collect(java.util.stream.Collectors.joining("\n"))));
        }
        var projection = BoundRequirementProjection.expandTracked(sections, task, bundle);
        assertThat(projection.coveredPending(sections)).isNotEmpty();
        assertThat(task.pendingDecisions()).isNotEmpty();
        assertThat(projection.coveredPending(sections)).allMatch(statement -> !statement.contains("2010"));
        assertThat(ConfirmedDecisionSet.fromBoundDecisions(task.confirmedDecisions()).decisions()).isEqualTo(task.confirmedDecisions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"为科研工作者写一封会议提醒邮件。", "把研究方案摘要翻译成英文，只输出译文。", "用Python统计日志状态码。"})
    void unrelatedTasksDoNotReceiveResearchExperimentInstructions(String raw) {
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var instruction = PromptRewriteStrategy.forTask(task, PromptFormat.COMPACT).instruction();
        assertThat(instruction).doesNotContain("同信息比较先取得并封存", "不能只列若干候选而将方法、评分、随机化全部退回用户");
        assertThat(instruction).contains("真正需用户决定", "不能压缩掉否定、例外");
    }

    @Test
    void researchGuidanceRetainsConcreteDesignAndInformationFlowBoundaries() {
        var task = EffectiveTaskViewFactory.create("撰写研究设计的方法部分，研究Plan问答对提示词质量的影响。", TemplateCode.AUTO, List.of(), List.of(), false);
        assertThat(PromptRewriteStrategy.forTask(task, PromptFormat.COMPACT).instruction())
                .contains("不能只列若干候选而将方法、评分、随机化全部退回用户", "同信息比较先取得并封存");
    }

    @Test
    void actual436438440ReplayTheAssemblerWithoutInventingSourceReferences() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/fixtures/source-ownership-actual-replay.json")) {
            for (var sample : mapper.readTree(stream).path("samples")) {
                String raw = sample.path("rawPrompt").asText();
                String original = sample.path("modelResponseRaw").asText();
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(original.getBytes(StandardCharsets.UTF_8))))
                        .isEqualTo(sample.path("modelResponseSha256").asText());
                var body = mapper.readTree(original);
                var answers = mapper.convertValue(sample.path("answers"), new TypeReference<List<com.promptoptimizer.enhancement.dto.PlanAnswer>>() { });
                var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
                var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
                var response = new EnhancementProviderResponse(mapper.convertValue(body.path("sections"), new TypeReference<List<PromptSection>>() { }),
                        "fixture", "fixture", true, List.of(), List.of(), mapper.treeToValue(body.path("assessment"), TaskEnhancementAssessment.class));
                var result = new OptimizationResultAssembler().assemble(response, context,
                        new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                        bundle, false, 1, raw, List.of(), List.of(), task, PromptFormat.COMPACT);
                assertThat(result.optimizedPrompt()).doesNotContain("{{REQ_");
                assertThat(result.appliedConstraints()).containsAll(bundle.visibleConstraints());
                assertThat(result.constraintGroups()).isEqualTo(bundle);
                if (sample.path("trace").asInt() == 436) {
                    assertThat(result.optimizedPrompt()).doesNotContain("任务相关约束：\n- 校验所有外部输入，并明确处理空值、非法值和边界条件。");
                    assertThat(result.optimizedPrompt()).contains("未实际运行的验证不得写成通过", "100", "599", "无效");
                }
                if (sample.path("trace").asInt() == 438) {
                    assertThat(result.optimizedPrompt()).contains("全额退款", "不改期", "到账日期", "已付款报名者");
                }
            }
        }
    }
}
