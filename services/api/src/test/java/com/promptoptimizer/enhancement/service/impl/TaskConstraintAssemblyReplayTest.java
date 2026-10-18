package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.service.impl.MybatisOptimizationHistoryService;
import com.promptoptimizer.history.service.impl.OptimizationHistoryPersistenceService;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在真实组装入口回放已观察到的标点重复，保护独立规则和来源列表。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskConstraintAssemblyReplayTest {
    private static final String RULE = "明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。";
    private static final String VARIANT = RULE.replace('，', '；');

    @Test
    void replaysTheObservedConstraintWithoutPretendingToHaveTheRawModelResponse() throws Exception {
        JsonNode fixture;
        try (var source = getClass().getResourceAsStream("/enhancement/mortality-20261016-observed.json")) {
            fixture = new ObjectMapper().readTree(source);
        }
        String raw = fixture.path("rawPrompt").asText();
        var constraints = new ConstraintCompleterImpl().completeForTask(emptyContext(),
                PermissionPolicyInput.empty(), true, TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        List<String> findings = new ObjectMapper().convertValue(fixture.path("ambiguities"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        var result = assemble(fixture.path("storedConstraintPrefix").asText(), constraints, false, raw, findings);
        assertThat(result.optimizedPrompt()).contains(VARIANT).doesNotContain("- " + RULE);
        assertThat(result.appliedConstraints()).contains(RULE);
        assertThat(result.ambiguities()).containsAll(findings);
        assertThat(result.optimizedPrompt()).contains("Arriaga", "报告正文", "参考寿命表");
        // 仅供隔离浏览器回放当前组装结果；不称为新模型原件或下游实际作品。
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                java.nio.file.Path.of("target", "task-constraint-offline-result.json").toFile(), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "- ", "* ", "• ", "1. ", "1、"})
    void acceptsOnlyACompleteCurrentRuleWithEquivalentClauseSeparators(String prefix) {
        for (boolean plan : List.of(false, true)) {
            var result = assemble(prefix + VARIANT, bundle(List.of(RULE)), plan);
            assertThat(result.optimizedPrompt()).contains(VARIANT).doesNotContain("任务相关约束：", "- " + RULE);
            assertThat(result.appliedConstraints()).contains(RULE);
        }
    }

    @Test
    void supportsAsciiClauseSeparatorsOnlyInOtherwisePlainChineseRules() {
        for (char separator : new char[]{',', ';'}) {
            String text = RULE.replace('，', separator);
            assertThat(assemble(text, bundle(List.of(RULE)), false).optimizedPrompt())
                    .contains(text).doesNotContain("任务相关约束：");
        }
    }

    @Test
    void keepsTheExistingExactMultiLineSourceContractCoverage() {
        String rule = "资料来源一：保留原文。\n资料来源二：不得推断版本。";
        assertThat(assemble(rule, bundle(List.of(rule)), false).optimizedPrompt())
                .contains(rule).doesNotContain("任务相关约束：");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "甲研究：\n- 明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "如果以后确认：\n- 明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "示例：\n- 明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "示例：\n### 约束\n- 明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "```text\n明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。\n```",
            "> 明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "如果资料齐全，明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "不得要求明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造。",
            "明确数据来源、研究对象、指标定义和统计口径；无法核实的数据与引用不得编造，演示数据除外。",
            "明确数据来源、研究对象、指标定义和统计口径。",
            "无法核实的数据与引用不得编造。"
    })
    void preservesTheWholeRequirementForPartialQuotedConditionalOrExceptedStatements(String providerText) {
        var result = assemble(providerText, bundle(List.of(RULE)), false);
        assertThat(result.optimizedPrompt()).contains("任务相关约束：\n- " + RULE);
        assertThat(result.appliedConstraints()).contains(RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "乙研究明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。",
            "明确数据来源、研究对象、指标定义和统计口径，获批后无法核实的数据与引用不得编造。",
            "明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造，演示数据除外。",
            "明确数据来源、研究对象、指标定义和统计口径，不得遗漏无法核实的数据与引用。"
    })
    void doesNotRemoveAnIndependentObjectConditionNegationOrException(String additionalRule) {
        var result = assemble(VARIANT, bundle(List.of(RULE, additionalRule)), false);
        assertThat(result.optimizedPrompt()).contains(VARIANT, "- " + additionalRule).doesNotContain("- " + RULE + "\n");
        assertThat(result.appliedConstraints()).contains(RULE, additionalRule);
    }

    @Test
    void keepsLiteralPunctuationNumbersAndProtectedPaths() {
        List<String> rules = List.of("状态必须为`ready,done`。", "阈值必须>1.5，保留原值。", "保护路径为data/a,b/**。");
        var result = assemble("状态必须为`ready;done`。\n阈值必须>=1.5；保留原值。\n保护路径为data/a;b/**。",
                bundle(rules), false);
        assertThat(result.optimizedPrompt()).contains("任务相关约束：", rules.get(0), rules.get(1), rules.get(2),
                "用户补充权限边界", "research/private/**", "明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。");
        assertThat(result.appliedConstraints()).containsAll(rules);
    }

    @Test
    void alsoKeepsTheWholeRuleWhenAnExceptionContainsItsExactOriginalSpelling() {
        var result = assemble(RULE + "但已获批的演示数据除外。", bundle(List.of(RULE)), false);
        assertThat(result.optimizedPrompt()).contains("任务相关约束：\n- " + RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"不得删除文件，修改配置。", "如果授权，读取原文。", "只保留当前结果，历史记录仍需确认。",
            "有权限时，导出原文。", "保留当前结果，若失败不得回填。"})
    void punctuationDoesNotChangeNegationOrConditionalScope(String rule) {
        String provider = rule.replace('，', '；');
        assertThat(assemble(provider, bundle(List.of(rule)), false).optimizedPrompt())
                .contains("任务相关约束：\n- " + rule);
    }

    @Test
    void roundTripsTheActualAssembledBodySectionsAndRuleSourcesThroughTheHistoryService() {
        var result = assemble(VARIANT, bundle(List.of(RULE)), false);
        var actor = TestActors.currentActor();
        var identity = actor.require();
        var mapper = mock(OptimizationRecordMapper.class);
        var persistence = mock(OptimizationHistoryPersistenceService.class);
        var saved = new java.util.concurrent.atomic.AtomicReference<OptimizationRecordEntity>();
        when(persistence.persist(any(), eq(identity))).thenAnswer(invocation -> {
            OptimizationRecordEntity entity = invocation.getArgument(0);
            entity.setCreatedAt(java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"));
            saved.set(entity);
            return entity.getId();
        });
        when(mapper.selectByIdAndScope(any(), eq(identity.tenantId()), eq(identity.workspaceId())))
                .thenAnswer(invocation -> saved.get());
        var history = new MybatisOptimizationHistoryService(mapper, persistence,
                mock(com.promptoptimizer.enhancement.service.EnhancementOrchestrator.class), actor,
                com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var request = new OptimizationRequest("撰写研究报告正文。", new ContextAnalysisRequest("", List.of()),
                EnhancementOptions.defaults(), List.of(), PermissionPolicyInput.empty());
        var id = history.save(request, result);
        var restored = history.get(id);
        assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(result.sections());
        assertThat(restored.appliedConstraints()).isEqualTo(result.appliedConstraints()).contains(RULE);
        assertThat(restored.optimizedPrompt()).doesNotContain("任务相关约束：");
        verify(mapper).selectByIdAndScope(id, identity.tenantId(), identity.workspaceId());
    }

    private ConstraintBundle bundle(List<String> taskRules) {
        return new ConstraintBundle(List.of("明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。"),
                taskRules, List.of("禁止读取或输出受保护路径：research/private/**。"));
    }

    private OptimizationResult assemble(String content, ConstraintBundle constraints, boolean plan) {
        return assemble(content, constraints, plan, "撰写研究报告正文。", List.of());
    }

    /** 只构造非原件字段；经过正式组装器，不直接测试一个脱离调用方的字符串工具。 */
    private OptimizationResult assemble(String content, ConstraintBundle constraints, boolean plan,
            String raw, List<String> findings) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "分析2015—2025年心脑血管疾病死亡率。"),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写研究报告正文，分析长期和季节趋势、性别、地区、人群与亚类；计算年度及性别的YLL和YLL率，采用Arriaga分解。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付有依据的报告正文，保留重要未知并继续其余内容。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", content)),
                "offline-fixture", "offline-fixture", false, findings);
        return new OptimizationResultAssembler().assemble(response, emptyContext(),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付正文。", "不编造数据。", "遵守原要求。"),
                List.of(), List.of(), plan, constraints, false, 1, raw, List.of(), List.of());
    }

    private ContextSnapshot emptyContext() {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
    }
}
