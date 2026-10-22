package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 从真实Provider调用边界核对补全准备、来源隔离、最终覆盖及实际调用计量。
 * 这里只锁定阶段与保护契约，语义收益另由同条件真实作品验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalPreparationTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-api-key");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void preparesContentBeforeGenerationWithoutTurningSuggestionsIntoSources() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(preparation()), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var input = json.readTree(sent.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(input.path("rawPrompt").asText()).isEqualTo("写邮件");
            assertThat(input.path("taskSources")).hasSize(1);
            assertThat(input.path("taskSources").get(0).path("id").asText()).isEqualTo("rawPrompt");
            assertThat(input.path("completionSuggestions").path("additions").get(0).path("text").asText())
                    .isEqualTo("写清希望收件人采取的下一步");
            assertThat(input.path("actualAnswers").isEmpty()).isTrue();
        }).andRespond(withSuccess(response(finalBody()), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        try (var audit = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 1), (tenant, fact) -> observations.add(fact)))) {
            var result = provider.enhance(input(), draft -> {
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
            assertThat(result.optimizedPrompt()).isEqualTo("请写邮件，写清希望收件人采取的下一步。");
        }
        assertThat(observations).hasSize(2).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.operation()).isEqualTo("prompt.optimize");
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void preparationCannotInventEvidenceAndStopsBeforeFinalGeneration() throws Exception {
        String fake = preparation().replace("\"rawPrompt\"", "\"completionSuggestions\"");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(fake), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(fake), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void preparationDoesNotPretendThatItsSuggestionsHaveAlreadyAppearedInFinalBody() throws Exception {
        String premature = finalBody();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(premature), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(premature), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void missingAnswerSourceIsRepairedWithoutDemandingPrematureBodyAnchors() throws Exception {
        var answer = new com.promptoptimizer.enhancement.dto.PlanAnswer("name", "采用什么称呼？", "使用内部称呼");
        var actual = new NaturalPromptInput("写邮件", input().context(), List.of(answer), List.of(), List.of(), null)
                .withStrategyVersion("15");
        String prepared = withAnswer(preparation(), false);
        String generated = withAnswer(finalBody(), true);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(preparation()), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String instruction = sent.path("messages").get(0).path("content").asText();
            assertThat(instruction).contains("actualAnswers.0", "三个review数组继续为空")
                    .doesNotContain("补齐出处及对应正文锚点");
        }).andRespond(withSuccess(response(prepared), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(generated), MediaType.APPLICATION_JSON));
        var result = provider.enhance(actual, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).contains("使用内部称呼");
        assertThat(actual.actualAnswers()).containsExactly(answer);
        server.verify();
    }

    @Test
    void planKeepsTheFourthVersionAndHasNoPreparationCall() throws Exception {
        var result = json.createObjectNode();
        result.set("taskUnderstanding", json.readTree(preparation()).path("taskUnderstanding"));
        result.put("summary", "任务可直接完善，不需用户补充普通表达设计");
        result.putArray("questions");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
        }).andRespond(withSuccess(response(result.toString()), MediaType.APPLICATION_JSON));
        var plan = provider.plan(input(), value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(plan.questions()).isEmpty();
        assertThat(json.writeValueAsString(plan)).doesNotContain("completionSuggestions", "taskUnderstanding");
        server.verify();
    }

    /** 同一原始来源用于两阶段；内部建议永远不是可引用的新来源。 */
    private NaturalPromptInput input() {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(), List.of(), List.of(), null).withStrategyVersion("15");
    }

    /** 准备阶段复用任务记录，但尚未产生正文覆盖或新的Plan问题。 */
    private String preparation() throws Exception {
        var task = task(false);
        return json.writeValueAsString(Map.of("taskUnderstanding", task));
    }

    /** 最终记录仍须把原有要求与拟议补充落到最终正文原句。 */
    private String finalBody() throws Exception {
        return json.writeValueAsString(Map.of("optimizedPrompt", "请写邮件，写清希望收件人采取的下一步。", "framework", "APE", "taskUnderstanding", task(true)));
    }

    /** 为阶段隔离构建相同来源与补充，只有最终覆盖随阶段变化。 */
    private NaturalTaskUnderstanding task(boolean finalStage) {
        return new NaturalTaskUnderstanding("写邮件", "邮件", List.of(new NaturalTaskUnderstanding.Requirement("写邮件",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")), List.of(NaturalTaskUnderstanding.Dimension.GOAL))),
                List.of(new NaturalTaskUnderstanding.Addition("写清希望收件人采取的下一步", "收件人知道如何回应", "未说明邮件期待的行动")), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION,
                        finalStage ? List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件"))) : List.of(),
                        finalStage ? List.of(new NaturalTaskReview.Coverage(0, List.of("写清希望收件人采取的下一步"))) : List.of(), List.of()));
    }

    /** 每份模拟HTTP响应仍携带实际计量字段，便于核对两阶段均进入总账。 */
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }

    /** 给两个阶段补入同一份真实回答；只在最终阶段建立对应的正文引用。 */
    private String withAnswer(String encoded, boolean finalStage) throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(encoded);
        var task = body.path("taskUnderstanding");
        ((com.fasterxml.jackson.databind.node.ArrayNode) task.path("requirements")).add(json.valueToTree(Map.of(
                "text", "使用内部称呼", "dimensions", List.of("USER_CONFIRMATION"),
                "evidence", List.of(Map.of("sourceId", "actualAnswers.0", "quote", "使用内部称呼")))));
        if (finalStage) {
            body.put("optimizedPrompt", body.path("optimizedPrompt").asText() + "使用内部称呼。");
            ((com.fasterxml.jackson.databind.node.ArrayNode) task.path("review").path("requirementCoverage"))
                    .add(json.valueToTree(Map.of("index", 1, "quotes", List.of("使用内部称呼"))));
        }
        return body.toString();
    }
}
