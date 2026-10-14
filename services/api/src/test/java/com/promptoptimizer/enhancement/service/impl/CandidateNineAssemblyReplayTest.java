package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 候选八真实响应经生产组装器重放，锁定平台追加及委托状态；不请求外部模型。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateNineAssemblyReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldNotAppendSharedStatusDelegationAndAlreadyCoveredRulesToActualPlanResponse() throws Exception {
        var input = fixture("q05-flash/input.json");
        String raw = input.path("rawPrompt").asText();
        var answers = new ArrayList<PlanAnswer>();
        for (var answer : fixture("q05-flash/actual-answers.json").path("answers"))
            answers.add(json.treeToValue(answer, PlanAnswer.class));
        var result = assemble(model(187), raw, answers);
        assertThat(result.optimizedPrompt()).doesNotContain("- 这是尚未实施的研究。", "- 我没有额外真实数据",
                "- 请根据原始研究目标提出可开展的推荐设计并说明适用条件；",
                "- 正文800至1200个汉字，附表另计。", "- 当前只是拟定研究，尚未实施。");
        assertThat(result.optimizedPrompt()).contains("尚未实施", "没有额外真实数据", "拟议", "800", "1200");
        // 委托允许提出方案，未决实际参数仍要保留，不能靠丢掉问题身份消除提醒。
        assertThat(result.optimizedPrompt()).doesNotContain("该问题尚未确定：这 4 类任务分别是什么？");
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
        assertThat(task.pendingDecisions()).hasSize(6);
        assertThat(task.modelInput().pendingDecisions()).allMatch(value -> value.designDelegated());
    }

    @Test
    void shouldNotReappendExactClausesAlreadyInTheActualDirectResponse() throws Exception {
        String raw = fixture("q05-flash/direct-equal-information/input.json").path("rawPrompt").asText();
        var result = assemble(model(190), raw, List.of());
        assertThat(result.optimizedPrompt()).doesNotContain("- 当前只是拟定研究，尚未实施。",
                "- 正文800至1200个汉字，附表另计。");
        assertThat(result.optimizedPrompt()).contains("800", "1200", "拟议");
    }

    /** 使用冻结响应而非手造更容易通过的四要素；元数据不影响本节点覆盖判断。 */
    private com.promptoptimizer.enhancement.domain.OptimizationResult assemble(JsonNode model, String raw,
            List<PlanAnswer> answers) throws Exception {
        var sections = new ArrayList<PromptSection>();
        for (var section : model.path("sections")) sections.add(json.treeToValue(section, PromptSection.class));
        var ambiguities = new ArrayList<String>();
        model.path("ambiguities").forEach(value -> ambiguities.add(value.asText()));
        var response = new EnhancementProviderResponse(sections, "deepseek", "deepseek-flash", false, ambiguities,
                List.of(), json.treeToValue(model.path("assessment"), TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var template = new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法正文。", "保持研究目标。", "遵守原文。");
        return new OptimizationResultAssembler().assemble(response, context, template, List.of(), answers,
                !answers.isEmpty(), List.of(), false, 1, raw);
    }

    /** 只读已提交原件，不访问凭据文件或另行补入平台遗漏的答案。 */
    private JsonNode fixture(String name) throws Exception {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (base != null) {
            Path file = base.resolve("docs/testing/evidence/me00a-candidate8-2026-10-14").resolve(name);
            if (Files.isRegularFile(file)) return json.readTree(Files.readString(file));
            base = base.getParent();
        }
        throw new IllegalStateException("缺少候选八原件：" + name);
    }

    private JsonNode model(int attempt) throws Exception {
        return json.readTree(fixture("traces/" + attempt + ".json").path("response").path("choices").get(0)
                .path("message").path("content").asText());
    }
}
