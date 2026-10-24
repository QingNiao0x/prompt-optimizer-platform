package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.MaterialVisibility;
import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptGroundedCompletionInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptRoutingRepairInstructions;
import com.promptoptimizer.provider.domain.NaturalTaskReview;
import com.promptoptimizer.provider.domain.NaturalTaskUnderstanding;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 43沿用41路由，并在受控响应上验证资料状态和待确认边界。
 * 预置正文只证明契约，不代表真实模型的作品质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalGroundedCompletionProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var settings = new OpenAiCompatibleProperties();
        settings.setEndpoint(URI.create(ENDPOINT));
        settings.setApiKey("fixture");
        settings.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings, (PlatformModelCatalog) null);
    }

    @Test
    void inherentQueryStillReachesTheWriterAndReceivesExecutionOnlyState() throws Exception {
        var input = input("请帮我查询全球使用AI最多的职业/行业", null, List.of());
        String body = "请查询全球使用AI最多的职业和行业，先给出主要发现，再说明领先行业、典型岗位和主要应用场景。";
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload ->
                assertThat(payload.path("materialVisibility").path("requestMaterials").asText()).isEqualTo("EXECUTION_ONLY"));
        expect(withAddition(writing(input, body), "说明职业和行业的主要应用场景", "典型岗位和主要应用场景"),
                NaturalPromptGroundedCompletionInstructions.ENHANCE, payload -> {
                    assertThat(payload.path("materialVisibility").path("requestMaterials").asText()).isEqualTo("EXECUTION_ONLY");
                    assertThat(payload.path("taskScope").path("response").path("basis").asText()).isEqualTo("INHERENT");
                });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        assertThat(NaturalPromptInstructions.enhancementInstruction("31")).isEqualTo(NaturalPromptInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.planningInstruction("43"))
                .isEqualTo(NaturalPromptInstructions.planningInstruction("41"));
        server.verify();
    }

    @Test
    void visibleMaterialAnswerAndExplicitAbsenceKeepTheirLabels() throws Exception {
        var context = new ContextSnapshot("周末活动", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("note.txt", "text", "报名人数已满", "", false)), List.of(), List.of(), "1");
        var input = input("请按报名人数写活动建议", context, List.of(new PlanAnswer("place", "地点？", "青禾中心二层会议室")));
        String body = "请按报名人数写活动建议。地点为青禾中心二层会议室，并说明报名人数已满时如何调整。";
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(writing(input, body), NaturalPromptGroundedCompletionInstructions.ENHANCE, payload -> {
            assertThat(payload.path("materialVisibility").path("requestMaterials").asText())
                    .isEqualTo(MaterialVisibility.State.VISIBLE_NOW.name());
            assertThat(states(payload)).containsEntry("context.fileSnippets.0", "VISIBLE_NOW")
                    .containsEntry("actualAnswers.0", "USER_OPEN");
        });
        assertThat(provider.enhance(input, value -> checked(value, input)).optimizedPrompt()).contains("青禾中心二层会议室");
        server.verify();
    }

    @Test
    void explicitAbsenceRemainsInTheBodyWhenTheUserAlreadyStatedIt() throws Exception {
        var absent = input("没有相关资料，请写一份社区活动建议。", null, List.of());
        String absentBody = "请写一份社区活动建议。目前没有相关资料，可提出拟议做法，不编造本社区现状。";
        expect(scope(absent.rawPrompt(), "UNSPECIFIED"), NaturalPromptRoutingRepairInstructions.PREPARE, payload ->
                assertThat(states(payload)).containsEntry("rawPrompt", "USER_ABSENT"));
        expect(writing(absent, absentBody), NaturalPromptGroundedCompletionInstructions.ENHANCE, payload -> {});
        assertThat(provider.enhance(absent, value -> checked(value, absent)).optimizedPrompt()).contains("没有相关资料");
        server.verify();
    }

    @Test
    void checklistLeadAndUngroundedAdditionAreRejectedWithoutChangingTheDefault() throws Exception {
        var input = input("请帮我查询全球使用AI最多的职业/行业", null, List.of());
        var checklist = writing(input, "请先补充统计口径。然后再查询职业和行业。");
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(checklist, NaturalPromptGroundedCompletionInstructions.ENHANCE, payload -> {});
        expect(checklist, null, payload -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void additionMustCiteAnObjectFromTheVisibleInput() throws Exception {
        var input = input("请比较两种通勤方式。", null, List.of());
        var response = withAddition(writing(input, "请比较两种通勤方式，说明时间和费用。"), "明确统计口径和资料边界", "说明时间和费用");
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(response, NaturalPromptGroundedCompletionInstructions.ENHANCE, payload -> {});
        expect(response, null, payload -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void openDecisionThatTheUserDidNotWriteIsKeptOutOfTheCopiedPrompt() throws Exception {
        var drifted = input("请比较两种通勤方式。", null, List.of());
        var decision = writing(drifted, "请比较两种通勤方式。必须先选定唯一统计视角。");
        ((ObjectNode) decision.path("taskUnderstanding")).set("openDecisions", json.valueToTree(List.of(
                new NaturalTaskUnderstanding.OpenDecision("必须先选定唯一统计视角", "会改变比较范围", ""))));
        expect(scope(drifted.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(decision, null, payload -> {});
        expect(decision, null, payload -> {});
        assertThatThrownBy(() -> provider.enhance(drifted, value -> checked(value, drifted))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private java.util.Map<String, String> states(JsonNode payload) {
        var states = new java.util.HashMap<String, String>();
        payload.path("materialVisibility").path("sources").forEach(source ->
                states.put(source.path("sourceId").asText(), source.path("state").asText()));
        return states;
    }

    private NaturalPromptInput input(String raw, ContextSnapshot context, List<PlanAnswer> answers) {
        return new NaturalPromptInput(raw, context, answers, List.of(), List.of(), null)
                .withStrategyVersion(NaturalPromptGroundedCompletionInstructions.VERSION);
    }

    private NaturalPromptDraft checked(NaturalPromptDraft value, NaturalPromptInput input) {
        var task = value.taskUnderstanding().resolve(input);
        task.review().validateEnhancement(task, value.optimizedPrompt());
        return value;
    }

    private ObjectNode scope(String raw, String basis) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", raw));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", raw, "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", basis, "description", basis.equals("UNSPECIFIED") ? "" : "原定回应",
                        "evidence", basis.equals("UNSPECIFIED") ? List.of() : evidence)));
    }

    private ObjectNode writing(NaturalPromptInput input, String body) {
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(task(input, body)));
        return response;
    }

    private ObjectNode withAddition(ObjectNode response, String text, String quote) {
        var task = (ObjectNode) response.path("taskUnderstanding");
        task.withArray("additions").addObject().put("text", text).put("gap", "原文没有展开该对象的处理").put("benefit", "让原定结果可以直接使用");
        task.withObject("review").withArray("additionCoverage").addObject().put("index", 0).putArray("quotes").add(quote);
        return response;
    }

    private NaturalTaskUnderstanding task(NaturalPromptInput input, String body) {
        var requirements = new java.util.ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new java.util.ArrayList<NaturalTaskReview.Coverage>();
        for (var source : input.taskSources()) {
            requirements.add(new NaturalTaskUnderstanding.Requirement(source.text(),
                    List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())),
                    List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
            if (body != null) coverage.add(new NaturalTaskReview.Coverage(coverage.size(), List.of(body.contains("\n") ? body.split("\n")[0] : body)));
        }
        return new NaturalTaskUnderstanding(input.rawPrompt(), "", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
    }

    private void expect(ObjectNode content, String expectedSystem, Consumer<JsonNode> check) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var requestJson = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            if (expectedSystem != null) assertThat(requestJson.path("messages").get(0).path("content").asText()).isEqualTo(expectedSystem);
            String user = requestJson.path("messages").get(1).path("content").asText();
            JsonNode payload = json.readTree(user.substring(user.indexOf('{')));
            String system = requestJson.path("messages").get(0).path("content").asText();
            if (system.contains("上次响应未满足协议")) {
                assertThat(system).contains("materialVisibility为EXECUTION_ONLY");
            }
            check.accept(payload);
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
