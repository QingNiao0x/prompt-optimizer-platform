package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 在真实Provider入口核对正文补全候选的单变量请求、阶段和现有保护。
 * 响应正文来自受控夹具，验证平台交付契约，不据此推断真实模型语义或作品质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalInternalDesignProviderTest {
    private static final String VERSION = "internal-design-v1";
    private static final String BASE = "core-repair-v4";
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final Path BASELINE = Path.of("../../docs/testing/evidence/natural-completion-boundary-2026-11-06/instructions");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void frozenV4StagesAndCandidatePlanKeepTheirOriginalBytes() throws Exception {
        assertThat(NaturalPromptPlanContextInstructions.PREPARE).isEqualTo(Files.readString(BASELINE.resolve("prepare.txt")));
        assertThat(NaturalPromptInstructions.enhancementInstruction(BASE)).isEqualTo(Files.readString(BASELINE.resolve("enhance.txt")));
        assertThat(NaturalPromptInstructions.planningInstruction(BASE)).isEqualTo(Files.readString(BASELINE.resolve("plan.txt")));
        assertThat(NaturalPromptInstructions.planningInstruction(VERSION)).isEqualTo(NaturalPromptInstructions.planningInstruction(BASE));
        assertThat(NaturalPromptInstructions.enhancementInstruction(VERSION))
                .contains("原动作或成果内部", "另一项独立成果", "拟议方案", "充分需求")
                .doesNotContain("不能另设要向用户展示或交付的内容", "是否新增行动或成果");
        assertThat(NaturalPromptStageInstructions.requiresFramework(VERSION))
                .isEqualTo(NaturalPromptStageInstructions.requiresFramework(BASE));
        assertThat(NaturalPromptInstructions.requiresTaskReview(VERSION)).isTrue();
    }

    /** 这些变体属于已公开的机制回归，正文由夹具提供，不能计作新题效果评测。 */
    static Stream<TaskCase> tasks() {
        return Stream.of(
                new TaskCase("查询", "请帮我查询全球使用AI最多的职业/行业",
                        "请查询全球AI使用较多的职业与行业，比较主要应用、使用程度与代表性发现，结合检索到的资料给出结论。", "INHERENT", List.of()),
                new TaskCase("修复", "定位并修复上传附件后的保存失败，保留其他保存行为。",
                        "请沿附件上传和保存链路定位并修复失败原因，保留其他保存行为，并检查修复涉及的正常与异常路径。", "INHERENT", List.of()),
                new TaskCase("报告", "仅写排查报告，说明现象、可能原因和验证方法，不修改文件。",
                        "仅写排查报告，说明现象、可能原因和验证方法，不修改文件。", "EXPLICIT", List.of()),
                new TaskCase("复述", "阅读材料后复述我的三项要求，按原顺序，不执行。",
                        "阅读材料后复述我的三项要求，按原顺序，不执行。", "EXPLICIT", List.of()),
                new TaskCase("等待", "阅读所给材料，理解后等待下一项任务，不修改文件，不交付说明文档。",
                        "阅读所给材料，理解后等待下一项任务，不修改文件，不交付说明文档。", "UNSPECIFIED", List.of()),
                new TaskCase("充分需求", "将“请于周五前回复”翻译成英文，只输出译文。",
                        "将“请于周五前回复”翻译成英文，只输出译文。", "EXPLICIT", List.of()),
                new TaskCase("Plan已答", "给客户写延期通知，日期和补偿由我决定，请先确认，措辞由AI设计。",
                        "给客户写延期通知，措辞由AI设计。日期稍后确定，不填写具体日期。不承诺补偿。", "EXPLICIT",
                        List.of(new PlanAnswer("date", "交付日期是什么？", "日期稍后确定，不填写具体日期。"),
                                new PlanAnswer("compensation", "如何补偿？", "不承诺补偿。"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tasks")
    void onlyWritingGuidanceChangesAcrossTheActualProviderRequests(TaskCase task) throws Exception {
        var baseline = enhance(task, BASE, null);
        var candidate = enhance(task, VERSION, null);
        assertThat(candidate.draft().optimizedPrompt()).isEqualTo(task.body());
        assertThat(candidate.draft().completedStages()).containsExactly(
                NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(candidate.requests()).hasSize(2);
        assertThat(baseline.requests()).hasSize(2);
        for (int stage = 0; stage < 2; stage++) {
            JsonNode oldRequest = normalized(baseline.requests().get(stage));
            JsonNode newRequest = normalized(candidate.requests().get(stage));
            if (stage == 1) {
                ((ObjectNode)newRequest.path("messages").get(0)).put("content",
                        oldRequest.path("messages").get(0).path("content").asText());
            }
            assertThat(newRequest).as("same model, parameters, sources, answers and stage payload").isEqualTo(oldRequest);
        }
    }

    @Test
    void proposedDesignUsesExistingAdditionRecordWithoutPretendingToBeUserEvidence() throws Exception {
        var task = tasks().findFirst().orElseThrow();
        var generated = enhance(task, VERSION, "design");
        assertThat(generated.draft().taskUnderstanding().additions()).hasSize(1);
        assertThat(generated.draft().optimizedPrompt()).isEqualTo(task.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-answer", "forged-source", "missing-body-quote"})
    void invalidSourcesAnswersOrAdditionCoverageRemainRejected(String defect) {
        var task = tasks().filter(item -> !item.answers().isEmpty()).findFirst().orElseThrow();
        assertThatThrownBy(() -> enhance(task, VERSION, defect)).isInstanceOf(ProviderException.class);
    }

    /** 同一输入分别调用基线与候选，受控响应只改变测试所声明的缺陷。 */
    private Generated enhance(TaskCase task, String version, String variant) throws Exception {
        var input = new NaturalPromptInput(task.raw(), new ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "fixture"), task.answers(), List.of(), List.of(), null)
                .withStrategyVersion(version);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        var provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog)null);
        var requests = new ArrayList<JsonNode>();
        expect(server, scope(task), requests);
        var body = body(input, task.body(), variant);
        expect(server, body, requests);
        if (List.of("missing-answer", "forged-source", "missing-body-quote").contains(variant == null ? "" : variant)) {
            expect(server, body, requests);
            try {
                provider.enhance(input, draft -> draft);
                fail("invalid controlled response must not be delivered");
            } finally {
                server.verify();
            }
        }
        var draft = provider.enhance(input, value -> value);
        server.verify();
        return new Generated(draft, requests);
    }

    /** 当前动作保留原委托来源，未指定独立成果时使用UNSPECIFIED，仍应进入正文生成。 */
    private ObjectNode scope(TaskCase task) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", task.raw()));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", task.raw(), "timing", "CURRENT", "condition", "",
                        "userEvidence", evidence, "detailEvidence", List.of())),
                "response", Map.of("basis", task.basis(), "description", "UNSPECIFIED".equals(task.basis()) ? "" : task.raw(),
                        "userEvidence", "UNSPECIFIED".equals(task.basis()) ? List.of() : evidence, "detailEvidence", List.of())));
    }

    /** 沿用现有来源与正文引文结构，仅按声明的变体遗漏答案、伪造来源或遗漏补充覆盖。 */
    private ObjectNode body(NaturalPromptInput input, String text, String variant) {
        var result = json.createObjectNode().put("optimizedPrompt", text).put("framework", "APE");
        var task = result.putObject("task").put("goal", input.rawPrompt()).put("deliverable", "")
                .put("inputReadiness", "SUFFICIENT");
        var requirements = task.putArray("requirements");
        for (var source : input.taskSources()) {
            if ("missing-answer".equals(variant) && source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER) continue;
            var item = requirements.addObject().put("text", source.text());
            item.putArray("dimensions").add(source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER ? "USER_CONFIRMATION" : "GOAL");
            item.putArray("evidence").addObject().put("sourceId", source.id())
                    .put("quote", "forged-source".equals(variant) ? "原始输入中不存在的原句" : source.text());
            item.putArray("bodyQuotes").add(text);
        }
        var additions = task.putArray("additions");
        if ("design".equals(variant) || "missing-body-quote".equals(variant)) {
            var addition = additions.addObject().put("text", "比较主要应用与代表性发现")
                    .put("gap", "原查询尚未明确比较内容")
                    .put("benefit", "使原查询结果直接回答使用情况");
            addition.putArray("bodyQuotes").add("missing-body-quote".equals(variant) ? "正文不存在的设计要求" : text);
        }
        task.putArray("openDecisions");
        return result;
    }

    /** 捕获实际序列化请求，避免只检查常量而漏掉正文、Plan状态或来源的版本路由。 */
    private void expect(MockRestServiceServer server, ObjectNode output, List<JsonNode> requests) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(output))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request ->
                requests.add(json.readTree(((MockClientHttpRequest)request).getBodyAsString())))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    /** 只归一化明确的候选版本标记，其余请求字节对应的结构继续逐项比较。 */
    private JsonNode normalized(JsonNode request) throws Exception {
        var normalized = request.deepCopy();
        var user = (ObjectNode)normalized.path("messages").get(1);
        String text = user.path("content").asText();
        int start = text.indexOf('{');
        var payload = (ObjectNode)json.readTree(text.substring(start));
        payload.put("strategyVersion", BASE);
        user.put("content", text.substring(0, start) + json.writeValueAsString(payload));
        return normalized;
    }

    /** 已公开机制回归及其受控正文，案例名称不参与生产路由。 */
    record TaskCase(String name, String raw, String body, String basis, List<PlanAnswer> answers) {
        @Override public String toString() { return name; }
    }

    /** 单次受控入口调用的交付结果和实际发送请求。 */
    record Generated(NaturalPromptDraft draft, List<JsonNode> requests) { }
}
