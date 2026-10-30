package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 工程修复独立回归：不重写历史31，检查实际调用、源码隔离及来源修复反馈。 */
class NaturalCoreRepairProviderTest {
    private static final String VERSION = "core-repair-v1";
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

    @ParameterizedTest
    @ValueSource(strings = {"EXPLICIT", "INHERENT", "UNSPECIFIED"})
    void everyResponseClassGeneratesBodyWithoutPastingMaterials(String basis) throws Exception {
        String raw = "请在 @demo/src/views/records 的新增与导入中校验调查日期。";
        var input = input(raw, VERSION);
        expect(scope(raw, basis, false), null, false);
        String body = "请在 @demo/src/views/records 的新增与导入中沿用同一调查日期规则，保留现有导入反馈方式。";
        expect(writing(input, body), null, true);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body).doesNotContain("SOURCE_ONLY", "提供的材料：");
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"actions[0].evidence", "response.evidence"})
    void missingUserAuthorityGetsSpecificFeedbackWithoutInventingEvidence(String field) throws Exception {
        String raw = "按附件规则编写脚本。";
        var input = input(raw, VERSION);
        var invalid = scope(raw, "EXPLICIT", true);
        if (field.equals("response.evidence")) ((ObjectNode) invalid.path("actions").get(0))
                .set("evidence", scope(raw, "EXPLICIT", false).path("actions").get(0).get("evidence"));
        expect(invalid, null, false);
        expect(scope(raw, "EXPLICIT", false), field, false);
        expect(writing(input, raw), null, true);
        var result = provider.enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(raw);
        assertThat(result.completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void failedRepairStillBlocksAndNeverFallsBackToOriginalPlusSource() throws Exception {
        var input = input("按附件规则编写脚本。", VERSION);
        expect(scope(input.rawPrompt(), "EXPLICIT", true), null, false);
        expect(scope(input.rawPrompt(), "EXPLICIT", true), "actions[0].evidence", false);
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void frozen31StillUsesItsOriginalRepairContract() throws Exception {
        var input = input("按附件规则编写脚本。", "31");
        expect(scope(input.rawPrompt(), "EXPLICIT", true), null, false);
        expect(scope(input.rawPrompt(), "EXPLICIT", true), "LEGACY_REPAIR", false);
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void readAndWaitKeepsItsBoundaryAfterGeneration() throws Exception {
        var input = input("只阅读代码并等待，不修改文件，也不写说明文档。", VERSION);
        expect(scope(input.rawPrompt(), "UNSPECIFIED", false), null, false);
        expect(writing(input, input.rawPrompt()), null, true);
        var result = provider.enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(input.rawPrompt());
        assertThat(result.completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    private NaturalPromptInput input(String raw, String version) {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("rules.md", "markdown", "SOURCE_ONLY 附件说明，不是新的委托。", "", false)),
                List.of(), List.of(), "fixture");
        return new NaturalPromptInput(raw, context, List.of(), List.of(), List.of(), null).withStrategyVersion(version);
    }

    private ObjectNode scope(String raw, String basis, boolean contextOnly) {
        var evidence = List.of(Map.of("sourceId", contextOnly ? "context.fileSnippets.0" : "rawPrompt",
                "quote", contextOnly ? "附件说明" : raw));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", raw, "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", basis, "description", basis.equals("UNSPECIFIED") ? "" : "原定回应",
                        "evidence", basis.equals("UNSPECIFIED") ? List.of() : evidence)));
    }

    private ObjectNode writing(NaturalPromptInput input, String body) {
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "", List.of(new NaturalTaskUnderstanding.Requirement(input.rawPrompt(),
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", input.rawPrompt())), List.of(NaturalTaskUnderstanding.Dimension.GOAL))),
                List.of(), List.of(), new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                List.of(new NaturalTaskReview.Coverage(0, List.of(body))), List.of(), List.of()));
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(task));
        return response;
    }

    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        task.review().validateEnhancement(task, draft.optimizedPrompt());
        return draft;
    }

    private void expect(ObjectNode content, String repair, boolean writer) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String system = payload.path("messages").get(0).path("content").asText();
            if (repair != null && !repair.equals("LEGACY_REPAIR")) {
                assertThat(system).contains(repair, "用户来源").doesNotContain("SOURCE_ONLY", "附件说明");
            } else if ("LEGACY_REPAIR".equals(repair)) {
                assertThat(system).doesNotContain("actions[0].evidence");
            }
            if (writer) assertThat(system).isEqualTo(NaturalPromptRoutingRepairInstructions.ENHANCE);
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
