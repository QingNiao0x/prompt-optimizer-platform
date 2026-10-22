package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证来源自检候选沿用一次生成和原协议，不引入准备、检测或修订阶段。
 * 模拟HTTP只验证调用与保护边界，实际提示词收益须另做同信息作品对照。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalSourceReviewTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String BODY = "请写邮件，发布时间尚未确定。";
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
    void sourceReviewUsesOneGenerationAndPreservesBoundAnswersAndAssistantSourceKinds() throws Exception {
        var bound = input();
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptSourceReviewInstructions.ENHANCE, false);
            assertGenerationInput(taskInput(request), bound);
        }).andRespond(withSuccess(response(draftResponse(task(true))), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 201), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(bound, draft -> {
                callbacks.incrementAndGet();
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(result.optimizedPrompt()).isEqualTo(BODY);
        assertThat(result.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
        assertThat(observations).hasSize(1).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void missingTaskRecordUsesOnlyTheExistingBoundedGenerationProtocolRepair() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertGenerationInput(taskInput(request), input()))
                .andRespond(withSuccess(response(json.writeValueAsString(Map.of("optimizedPrompt", BODY, "framework", "APE"))),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertGenerationInput(taskInput(request), input());
            assertSystem(request, NaturalPromptSourceReviewInstructions.ENHANCE, true);
        }).andRespond(withSuccess(response(draftResponse(task(true))), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(), draft -> {
            callbacks.incrementAndGet();
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(result.optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    @Test
    void taskRecordCannotOmitAnActualAnswerEvenWhenItsBodyMentionsTheUnknownState() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(task(false))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertGenerationInput(taskInput(request), input());
            assertSystem(request, NaturalPromptSourceReviewInstructions.ENHANCE, true);
        }).andRespond(withSuccess(response(draftResponse(task(true))), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(), draft -> {
            callbacks.incrementAndGet();
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().review().requirementCoverage().get(1).quotes())
                .containsExactly("发布时间尚未确定");
        server.verify();
    }

    @Test
    void finalCoverageStillRejectsAnAnchorAbsentFromTheGeneratedBody() throws Exception {
        var valid = task(true);
        var wrongCoverage = new NaturalTaskUnderstanding(valid.goal(), valid.deliverable(), valid.requirements(),
                valid.additions(), valid.openDecisions(), new NaturalTaskReview(valid.review().inputReadiness(),
                List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件")),
                        new NaturalTaskReview.Coverage(1, List.of("发布时间为周五"))), List.of(), List.of()));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(wrongCoverage)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertGenerationInput(taskInput(request), input()))
                .andRespond(withSuccess(response(draftResponse(valid)), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(), draft -> {
            callbacks.incrementAndGet();
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(result.optimizedPrompt()).isEqualTo(BODY);
        assertThat(result.taskUnderstanding().review().requirementCoverage().get(1).quotes())
                .containsExactly("发布时间尚未确定");
        server.verify();
    }

    @Test
    void planKeepsOneFourthVersionCallAndDoesNotStartSourceReviewGeneration() throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input().context(), List.of(), input().conversation(), List.of(), null)
                .withStrategyVersion("20");
        var planTask = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goal()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        String plan = json.writeValueAsString(Map.of("summary", "任务充分", "questions", List.of(), "taskUnderstanding", planTask));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.planningInstruction("4"), false);
            assertGenerationInput(taskInput(request), planInput);
        }).andRespond(withSuccess(response(plan), MediaType.APPLICATION_JSON));
        var result = provider.plan(planInput, value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(result.questions()).isEmpty();
        server.verify();
    }

    /** 原始输入和来源目录保留；不把首稿、诊断、补全提案或建议填入真实回答。 */
    private void assertGenerationInput(JsonNode payload, NaturalPromptInput input) {
        assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
        assertThat(payload.path("conversation")).isEqualTo(json.valueToTree(input.conversation()));
        assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
        assertThat(payload.path("strategyVersion").asText()).isEqualTo("20");
        for (String privateStage : List.of("completionSuggestions", "fidelityDraft", "fidelityFindings", "fidelityEditTargets")) {
            assertThat(payload.has(privateStage)).isFalse();
        }
        var kinds = new LinkedHashMap<String, String>();
        payload.path("taskSources").forEach(source -> kinds.put(source.path("id").asText(), source.path("kind").asText()));
        assertThat(kinds).containsEntry("rawPrompt", "ORIGINAL_REQUEST").containsEntry("conversation.0", "ASSISTANT_CONVERSATION");
        if (!input.actualAnswers().isEmpty()) assertThat(kinds).containsEntry("actualAnswers.0", "BOUND_PLAN_ANSWER");
    }

    /** 协议修复只能接在同一版本指导后，不能转换为其他阶段。 */
    private void assertSystem(org.springframework.http.client.ClientHttpRequest request, String instruction, boolean repaired)
            throws java.io.IOException {
        var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        String system = sent.path("messages").get(0).path("content").asText();
        if (repaired) assertThat(system).startsWith(instruction).isNotEqualTo(instruction);
        else assertThat(system).isEqualTo(instruction);
    }

    private JsonNode taskInput(org.springframework.http.client.ClientHttpRequest request) throws java.io.IOException {
        var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        String user = sent.path("messages").get(1).path("content").asText();
        return json.readTree(user.substring(user.indexOf('{')));
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test"), List.of(new PlanAnswer("q_time", "发布时间？", "发布时间还没定。")),
                List.of(new ConversationMessage("assistant", "建议周五发布；用户尚未确认。")), List.of(), null)
                .withStrategyVersion("20");
    }

    private NaturalTaskUnderstanding.Requirement goal() {
        return new NaturalTaskUnderstanding.Requirement("写邮件", List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
    }

    /** 同一正文不替代原始答复的记录；缺少题答出处仍应走既有来源保护。 */
    private NaturalTaskUnderstanding task(boolean includeAnswer) {
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        requirements.add(goal());
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        coverage.add(new NaturalTaskReview.Coverage(0, List.of("写邮件")));
        if (includeAnswer) {
            requirements.add(new NaturalTaskUnderstanding.Requirement("发布时间仍未知",
                    List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "发布时间还没定。")),
                    List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
            coverage.add(new NaturalTaskReview.Coverage(1, List.of("发布时间尚未确定")));
        }
        return new NaturalTaskUnderstanding("写邮件", "邮件", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION, coverage, List.of(), List.of()));
    }

    private String draftResponse(NaturalTaskUnderstanding task) throws Exception {
        return json.writeValueAsString(Map.of("optimizedPrompt", BODY, "framework", "APE", "taskUnderstanding", task));
    }

    /** 模拟响应仍带用量，以便单次生成和失败修复能够按真实尝试计数。 */
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
