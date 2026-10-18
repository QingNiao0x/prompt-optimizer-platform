package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整句覆盖只缩减任务规则的追加，不修改来源、固定条款或模型原件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskConstraintWholeProjectionTest {
    private static final String INPUT = "校验所有外部输入，并明确处理空值、非法值和边界条件。";
    private static final String ERROR = "处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。";
    private static final String TEST = "为核心逻辑补充正常、异常和边界场景测试。";

    @Test
    void completeErrorHandlingOnlyDiffersByItsFinalStatementSeparator() {
        assertThat(TaskConstraintCoverage.uncovered(sections("", ERROR.replace('。', '；')), List.of(ERROR))).isEmpty();
    }

    @Test
    void completeTaskTestObligationIsVisibleAfterTheCurrentTaskIntro() {
        var body = sections("", "");
        body.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务",
                "实现一个获取网页标题的功能：\n5. " + TEST.replace('。', '；')));
        assertThat(TaskConstraintCoverage.uncovered(body, List.of(TEST))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "校验传入的URL，对空值、非法格式给出明确提示。",
            "校验所有外部输入。",
            "明确处理空值、非法值和边界条件。",
            "仅对支付接口校验所有外部输入，并明确处理空值、非法值和边界条件。",
            "如果获批，校验所有外部输入，并明确处理空值、非法值和边界条件。",
            "校验所有外部输入，并明确处理空值、非法值和边界条件；但仅限已获批接口。",
            "以后再校验所有外部输入，并明确处理空值、非法值和边界条件。",
            "无需遵守校验所有外部输入，并明确处理空值、非法值和边界条件。",
            "```text\n校验所有外部输入，并明确处理空值、非法值和边界条件。\n```",
            "> 校验所有外部输入，并明确处理空值、非法值和边界条件。"
    })
    void partialConditionalQuotedOrOtherObjectRulesCannotCoverAllExternalInput(String evidence) {
        assertThat(TaskConstraintCoverage.uncovered(sections("", evidence), List.of(INPUT))).containsExactly(INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果获批：", "另一任务：", "实现另一个任务：", "示例：\n## 约束",
            "实现旧方案（仅供参考）：", "实现满足特定条件的功能：", "历史规则", "示例", "如果获批，",
            "1. 如果获批，", "1) 如果获批，", "1、 如果获批，", "另一任务", "其他项目"})
    void unrelatedOrConditionalHeadingCannotGrantAuthorityToTheFollowingTaskTest(String heading) {
        var body = sections("", "");
        body.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", heading + "\n- " + TEST));
        assertThat(TaskConstraintCoverage.uncovered(body, List.of(TEST))).containsExactly(TEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果获批，", "历史规则", "示例",
            "1. 如果获批，", "1) 如果获批，", "1、 如果获批，", "另一任务", "其他项目"})
    void legacyConstraintEntryCannotBorrowACompleteNextLineFromAnotherScope(String prefix) {
        assertThat(TaskConstraintCoverage.uncovered(sections("", prefix + "\n" + ERROR), List.of(ERROR))).containsExactly(ERROR);
    }

    @Test
    void multiLineContractInsideACodeFenceDoesNotBypassCurrentScope() {
        String rule = "资料一：保留原文。\n资料二：不得推断版本。";
        assertThat(TaskConstraintCoverage.uncovered(sections("", "```text\n" + rule + "\n```"), List.of(rule))).containsExactly(rule);
    }

    @Test
    void legitimateHistoryFeatureIsStillACurrentTaskRatherThanAHistoricalScope() {
        var body = sections("", "");
        body.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "实现历史记录功能：\n- " + TEST));
        assertThat(TaskConstraintCoverage.uncovered(body, List.of(TEST))).isEmpty();
    }

    @Test
    void completeWholeRuleDoesNotSplitNegationOrMultiLineSourceContracts() {
        String negative = "不得删除文件，修改配置。";
        assertThat(TaskConstraintCoverage.uncovered(sections("", negative.replace('，', '；')), List.of(negative)))
                .containsExactly(negative);
        String multiple = "资料一：保留原文。\n资料二：不得推断版本。";
        assertThat(TaskConstraintCoverage.uncovered(sections("资料一：保留原文。", "资料二：不得推断版本。"), List.of(multiple)))
                .containsExactly(multiple);
    }

    @Test
    void real383AssemblySkipsOnlyTheTwoProvenCompleteObligationsAndPreservesSources() throws Exception {
        var result = replay("REQUESTS-STRUCTURED");
        String constraints = result.sections().stream().filter(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .findFirst().orElseThrow().content();
        assertThat(constraints).contains("任务相关约束：\n- " + INPUT)
                .doesNotContain("\n- " + ERROR, "\n- " + TEST);
        assertThat(result.appliedConstraints()).contains(INPUT, ERROR, TEST);
        assertThat(result.optimizedPrompt()).contains("平台强制约束", "生产环境配置", "人工确认");
    }

    @Test
    void real382AssemblyRetainsTheWholeInputRuleWhenOnlyUrlChecksAreProvided() throws Exception {
        var result = replay("REQUESTS-COMPACT");
        assertThat(result.optimizedPrompt()).contains("任务相关约束：\n- " + INPUT);
        assertThat(result.appliedConstraints()).contains(INPUT, ERROR, TEST);
    }

    /** 原始字符串保持原样；缺省presentation只沿用Provider已有兼容口径，不补实质信息。 */
    private OptimizationResult replay(String id) throws Exception {
        var mapper = new ObjectMapper();
        JsonNode sample = null;
        try (var stream = getClass().getResourceAsStream("/fixtures/candidate34-task-constraint-replay.json")) {
            for (var item : mapper.readTree(stream).path("samples")) if (item.path("id").asText().equals(id)) sample = item;
        }
        if (sample == null) throw new IllegalArgumentException("Missing replay");
        String rawModel = sample.path("modelResponseRaw").asText();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawModel.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(sample.path("modelResponseSha256").asText());
        var original = mapper.readTree(rawModel);
        String raw = sample.path("rawPrompt").asText();
        var parts = mapper.convertValue(original.path("sections"), new com.fasterxml.jackson.core.type.TypeReference<List<PromptSection>>() { });
        var assessment = original.path("assessment").deepCopy();
        if (!assessment.has("presentation")) ((com.fasterxml.jackson.databind.node.ObjectNode) assessment).put("presentation", "STRUCTURED");
        var response = new EnhancementProviderResponse(parts, "deepseek", "deepseek-flash", false, List.of(), List.of(),
                mapper.treeToValue(assessment, TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        return new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不添加未授权示例。"),
                List.of(), List.of(), false, bundle, false, 1, raw, List.of(), List.of());
    }

    private Map<PromptSectionType, PromptSection> sections(String output, String constraints) {
        Map<PromptSectionType, PromptSection> body = new EnumMap<>(PromptSectionType.class);
        body.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", output));
        body.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints));
        return body;
    }
}
