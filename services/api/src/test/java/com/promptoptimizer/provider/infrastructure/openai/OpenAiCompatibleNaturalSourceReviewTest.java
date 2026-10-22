package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证来源自检、信息状态与来源原句指导候选沿用一次生成和原协议，不引入准备、检测或修订阶段。
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

    @ParameterizedTest
    @ValueSource(strings = {"20", "25", "26"})
    void sourceReviewUsesOneGenerationAndPreservesBoundAnswersAndAssistantSourceKinds(String version) throws Exception {
        var bound = input(version);
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction(version), false);
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

    @ParameterizedTest
    @ValueSource(strings = {"20", "25", "26"})
    void missingTaskRecordUsesOnlyTheExistingBoundedGenerationProtocolRepair(String version) throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertGenerationInput(taskInput(request), input(version)))
                .andRespond(withSuccess(response(json.writeValueAsString(Map.of("optimizedPrompt", BODY, "framework", "APE"))),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertGenerationInput(taskInput(request), input(version));
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction(version), true);
        }).andRespond(withSuccess(response(draftResponse(task(true))), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(version), draft -> {
            callbacks.incrementAndGet();
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(result.optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "25", "26"})
    void taskRecordCannotOmitAnActualAnswerEvenWhenItsBodyMentionsTheUnknownState(String version) throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(task(false))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertGenerationInput(taskInput(request), input(version));
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction(version), true);
        }).andRespond(withSuccess(response(draftResponse(task(true))), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(version), draft -> {
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

    @ParameterizedTest
    @ValueSource(strings = {"20", "25", "26"})
    void finalCoverageStillRejectsAnAnchorAbsentFromTheGeneratedBody(String version) throws Exception {
        var valid = task(true);
        var wrongCoverage = new NaturalTaskUnderstanding(valid.goal(), valid.deliverable(), valid.requirements(),
                valid.additions(), valid.openDecisions(), new NaturalTaskReview(valid.review().inputReadiness(),
                List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件")),
                        new NaturalTaskReview.Coverage(1, List.of("发布时间为周五"))), List.of(), List.of()));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(wrongCoverage)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertGenerationInput(taskInput(request), input(version)))
                .andRespond(withSuccess(response(draftResponse(valid)), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var result = provider.enhance(input(version), draft -> {
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

    @ParameterizedTest
    @ValueSource(strings = {"20", "25", "26"})
    void planKeepsOneFourthVersionCallAndDoesNotStartSourceReviewGeneration(String version) throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input(version).context(), List.of(), input(version).conversation(), List.of(), null)
                .withStrategyVersion(version);
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

    /** 原件中的语义失败原样保留；这里只检查25/26仍使用旧来源/覆盖协议，不能把回放通过当作质量通过。 */
    @ParameterizedTest
    @ValueSource(strings = {"4", "25", "26"})
    void frozenFourthResponseKeepsItsCompleteSourcesAndCoverageWithoutProtocolMigration(String version) throws Exception {
        JsonNode fixture;
        try (var resource = getClass().getResourceAsStream("/provider/natural-information-state/fourth-progress-response.json")) {
            assertThat(resource).isNotNull();
            fixture = json.readTree(resource);
        }
        String originalResponse = fixture.path("responseContent").asText();
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(originalResponse.getBytes(StandardCharsets.UTF_8)));
        assertThat(digest).isEqualTo("a2847ef8bdd5920bd16721b0c176897f5feaa1b9513d58465c914954ee157adf")
                .isEqualTo(fixture.path("responseContentSha256").asText());
        var inputNode = (ObjectNode) fixture.path("input").deepCopy();
        inputNode.remove("taskSources");
        inputNode.putNull("modelId");
        var bound = json.treeToValue(inputNode, NaturalPromptInput.class).withStrategyVersion(version);
        JsonNode sources = json.valueToTree(bound.taskSources());
        assertThat(sources).isEqualTo(fixture.path("input").path("taskSources"));
        var original = json.readTree(originalResponse);
        var originalTask = json.treeToValue(original.path("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(bound);
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction(version), false);
            assertGenerationInput(taskInput(request), bound);
        }).andRespond(withSuccess(response(originalResponse), MediaType.APPLICATION_JSON));
        var result = provider.enhance(bound, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(original.path("optimizedPrompt").asText());
        assertThat(result.taskUnderstanding()).isEqualTo(originalTask);
        assertThat(result.taskUnderstanding().requirements()).hasSize(4);
        assertThat(result.taskUnderstanding().review().requirementCoverage()).hasSize(4);
        assertThat(result.taskUnderstanding().review().additionCoverage()).hasSize(2);
        server.verify();
    }

    /** 复杂否定、证据范围及独立条件均为合法正文，指导升级不能通过关键词过滤改写或误拒它们。 */
    @ParameterizedTest
    @ValueSource(strings = {"4", "25", "26"})
    void compoundInformationStatesPassThroughWithBothActualAnswersAndAllEvidenceIntact(String version) throws Exception {
        String raw = "请把资料与我的答复整理成可发送的状态消息，只输出一条消息。";
        String material = "当前资料未附记录，不代表记录不存在；仓库复点确认备用适配器为0，但现用设备正常；"
                + "恢复演练方案已确认，演练尚未开始；只有选邮寄的人要填地址，现场领取不用。";
        var answers = List.of(new PlanAnswer("q_reply", "客户回复了吗？", "我还不能确定客户有没有回复。"),
                new PlanAnswer("q_gate", "何时通知门岗？", "若客户确认周四到场才通知门岗，否则继续等回复。"));
        var bound = new NaturalPromptInput(raw, new ContextSnapshot(material, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "test"), answers, List.of(), List.of(), null).withStrategyVersion(version);
        String unknown = "客户回复情况仍不确定";
        String condition = "仅当客户确认周四到场才通知门岗，否则继续等回复";
        String body = "请只输出一条消息，保留以下资料范围和条件：\n" + material + "\n" + unknown + "。" + condition + "。";
        var requirements = List.of(
                sourcedRequirement("交付一条消息", "rawPrompt", "只输出一条消息", NaturalTaskUnderstanding.Dimension.FORMAT),
                sourcedRequirement(material, "context.customDescription", material, NaturalTaskUnderstanding.Dimension.CONDITION),
                sourcedRequirement(unknown, "actualAnswers.0", answers.get(0).answer(), NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION),
                sourcedRequirement(condition, "actualAnswers.1", answers.get(1).answer(), NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION));
        var task = new NaturalTaskUnderstanding("整理状态消息", "一条消息", requirements, List.of(),
                List.of(new NaturalTaskUnderstanding.OpenDecision("客户是否回复", "不声称已回复或尚未回复", "",
                        NaturalTaskUnderstanding.DecisionKind.USER_INFORMATION)),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("只输出一条消息")),
                                new NaturalTaskReview.Coverage(1, List.of(material)),
                                new NaturalTaskReview.Coverage(2, List.of(unknown)),
                                new NaturalTaskReview.Coverage(3, List.of(condition))), List.of(), List.of()));
        String wire = json.writeValueAsString(Map.of("optimizedPrompt", body, "framework", "APE", "taskUnderstanding", task));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction(version), false);
            assertGenerationInput(taskInput(request), bound);
        }).andRespond(withSuccess(response(wire), MediaType.APPLICATION_JSON));
        var result = provider.enhance(bound, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(body);
        assertThat(result.taskUnderstanding()).isEqualTo(task.resolve(bound));
        assertThat(result.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.PROJECT_CONTEXT);
        assertThat(result.taskUnderstanding().requirements().get(2).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.0");
        assertThat(result.taskUnderstanding().requirements().get(3).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.1");
        server.verify();
    }

    /** 用原始连续引用构造协议夹具，不把测试自己写的摘要当作真实模型语义结果。 */
    private NaturalTaskUnderstanding.Requirement sourcedRequirement(String text, String sourceId, String quote,
            NaturalTaskUnderstanding.Dimension dimension) {
        return new NaturalTaskUnderstanding.Requirement(text, List.of(new NaturalTaskUnderstanding.Evidence(sourceId, quote)),
                List.of(dimension));
    }

    /** 原始输入和来源目录保留；不把首稿、诊断、补全提案或建议填入真实回答。 */
    private void assertGenerationInput(JsonNode payload, NaturalPromptInput input) {
        assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        assertThat(payload.path("context")).isEqualTo(json.valueToTree(input.context()));
        assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
        assertThat(payload.path("conversation")).isEqualTo(json.valueToTree(input.conversation()));
        assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
        assertThat(payload.path("strategyVersion").asText()).isEqualTo(input.strategyVersion());
        for (String privateStage : List.of("completionSuggestions", "fidelityDraft", "fidelityFindings", "fidelityEditTargets")) {
            assertThat(payload.has(privateStage)).isFalse();
        }
        var kinds = new LinkedHashMap<String, String>();
        payload.path("taskSources").forEach(source -> kinds.put(source.path("id").asText(), source.path("kind").asText()));
        assertThat(kinds).containsEntry("rawPrompt", "ORIGINAL_REQUEST");
        if (!input.conversation().isEmpty()) assertThat(kinds).containsEntry("conversation.0", "ASSISTANT_CONVERSATION");
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

    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test"), List.of(new PlanAnswer("q_time", "发布时间？", "发布时间还没定。")),
                List.of(new ConversationMessage("assistant", "建议周五发布；用户尚未确认。")), List.of(), null)
                .withStrategyVersion(version);
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
