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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放候选三十三实际模型原件，区分模型已有要求与平台再次投影；不调用模型或执行作品。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AssemblyProjectionReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"MAIL-COMPACT", "MAIL-STRUCTURED"})
    void doesNotReappendTheCompleteMailRuleAlreadyDeliveredAcrossOutputAndConstraints(String id) throws Exception {
        var sample = sample(id);
        var result = assemble(sample);
        assertThat(result.optimizedPrompt()).contains("只输出邮件正文，不超过100个汉字", "不添加示例")
                .doesNotContain("用户明确规则（须遵守平台权限边界）：");
        assertThat(result.appliedConstraints()).contains("明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。");
        assertThat(result.ambiguities()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH-COMPACT", "PATCH-STRUCTURED"})
    void onlyProjectsUncoveredJavaDeliveryObligationsInsteadOfAppendingTheWholeGuidance(String id) throws Exception {
        var result = assemble(sample(id));
        String output = result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content();
        assertThat(output).doesNotContain("Java21交付：保留已定类名、方法签名、业务对象及异常类型，使用Java21确实存在的标准库API；");
        assertThat(result.optimizedPrompt()).contains("PatchMerger.java", "PatchMergerTest.java", "未验证", "平台强制约束");
        assertThat(result.appliedConstraints()).anyMatch(rule -> rule.contains("密码、Token、API Key 或私钥"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTPUT", "CONSTRAINTS"})
    void quotedCodeInTheActualAssemblyPathCannotSuppressTheMissingCurrentRule(String location) throws Exception {
        var type = PromptSectionType.valueOf(location);
        String required = type == PromptSectionType.OUTPUT ? "只输出邮件正文，不超过100个汉字" : "不添加示例。";
        var result = assemble(sample("MAIL-COMPACT"), Map.of(type, "```text\n" + required + "\n```"));
        String constraints = result.sections().stream().filter(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .findFirst().orElseThrow().content();
        assertThat(constraints).contains("用户明确规则（须遵守平台权限边界）：\n- " + required);
        assertThat(result.sections().stream().filter(section -> section.type() == type).findFirst().orElseThrow().content())
                .contains("```text\n" + required + "\n```");
    }

    /** 原始模型字符串及哈希随夹具保留，不把离线重组装说成新生成的结果。 */
    private JsonNode sample(String id) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/candidate33-assembly-projection-replay.json")) {
            for (JsonNode sample : mapper.readTree(input).path("samples")) {
                if (!sample.path("id").asText().equals(id)) continue;
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(sample.path("modelResponseRaw").asText().getBytes(StandardCharsets.UTF_8)));
                assertThat(digest).isEqualTo(sample.path("modelResponseSha256").asText());
                return sample;
            }
        }
        throw new IllegalArgumentException("Missing replay case");
    }

    private OptimizationResult assemble(JsonNode sample) throws Exception {
        return assemble(sample, Map.of());
    }

    /** 仅修改明确标注的反例字段；其他模型原件字段及原始用户要求保持不变。 */
    private OptimizationResult assemble(JsonNode sample, Map<PromptSectionType, String> overrides) throws Exception {
        JsonNode original = mapper.readTree(sample.path("modelResponseRaw").asText());
        String raw = sample.path("rawPrompt").asText();
        var sections = mapper.convertValue(original.path("sections"), new com.fasterxml.jackson.core.type.TypeReference<List<PromptSection>>() { })
                .stream().map(section -> new PromptSection(section.type(), section.title(),
                        overrides.getOrDefault(section.type(), section.content()))).toList();
        var assessmentNode = original.path("assessment").deepCopy();
        // 371原件缺省内部presentation；复用Provider已有的STRUCTURED兼容口径，不补任务/事实或改写原件。
        if (!assessmentNode.has("presentation")) ((com.fasterxml.jackson.databind.node.ObjectNode) assessmentNode)
                .put("presentation", TaskEnhancementAssessment.Presentation.STRUCTURED.name());
        var assessment = mapper.treeToValue(assessmentNode, TaskEnhancementAssessment.class);
        var response = new EnhancementProviderResponse(sections, "deepseek", "deepseek-flash", false, List.of(), List.of(), assessment);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var constraints = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        return new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplate(TemplateCode.AUTO, "保留原定交付。", "遵守原要求。", "不添加未授权示例。"),
                List.of(), List.of(), false, constraints, false, 1, raw, List.of(), List.of());
    }
}
