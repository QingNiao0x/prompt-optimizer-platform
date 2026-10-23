package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 新阶段协议减少模型重复字段后，仍须通过原来源、完整实际题答和正文覆盖保护。
 * HTTP夹具仅验证协议与计量边界，不以模拟正文宣称语义质量提升或服务端身份验收完成。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalPhaseProtocolTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String RAW = "请写一条活动通知，50字以内，只输出正文；时间未定就写待定。";
    private static final String BODY = "请写一条50字以内的活动通知，只输出正文；时间待定，地点待定。";
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
    @ValueSource(strings = {"21", "22", "23", "24", "27"})
    void validEnhancementUsesOneCallAndNormalizesAllOriginalEvidence(String version) throws Exception {
        expect(1, enhancement(), input(version));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft draft;
        try (var ignored = audit(observations)) {
            draft = provider.enhance(input(version), this::checkDraft);
        }
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(draft.taskUnderstanding().review().requirementCoverage()).hasSize(4);
        assertThat(draft.taskUnderstanding().review().planImpacts()).isEmpty();
        assertThat(draft.taskUnderstanding().openDecisions()).hasSize(1);
        assertThat(draft.taskUnderstanding().openDecisions().getFirst().questionId()).isEmpty();
        assertThat(draft.taskUnderstanding().requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        assertThat(draft.taskUnderstanding().requirements().subList(2, 4))
                .allSatisfy(requirement -> assertThat(requirement.evidence().getFirst().origin())
                        .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().usage().totalTokens()).isEqualTo(15L);
        server.verify();
    }

    @Test
    void missingOneOfTwoIdenticalAnswersIsRepairedWithoutMergingTheirSources() throws Exception {
        var bad = enhancement();
        ((ArrayNode) bad.path("task").path("requirements")).remove(3);
        expect(1, bad, input("21"));
        expect(1, enhancement(), input("21"), system -> {
            String instruction = NaturalPromptInstructions.enhancementInstruction("21");
            assertThat(system).startsWith(instruction);
            assertThat(system.substring(instruction.length())).contains("补齐出处及对应正文锚点");
        });
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft draft;
        try (var ignored = audit(observations)) {
            draft = provider.enhance(input("21"), this::checkDraft);
        }
        assertThat(draft.taskUnderstanding().requirements().get(2).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.0");
        assertThat(draft.taskUnderstanding().requirements().get(3).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.1");
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @Test
    void additionKeepsItsGapBenefitAndFinalBodyAnchorAfterNormalization() throws Exception {
        var content = enhancement();
        content.put("optimizedPrompt", BODY + "使用简明语言。");
        ((ArrayNode) content.path("task").path("additions")).add(json.readTree("""
                {"text":"使用简明语言","gap":"原文未说明表达方式","benefit":"便于收件人快速阅读","bodyQuotes":["使用简明语言"]}
                """));
        expect(1, content, input("21"));
        var draft = provider.enhance(input("21"), this::checkDraft);
        var addition = draft.taskUnderstanding().additions().getFirst();
        assertThat(addition.gap()).isEqualTo("原文未说明表达方式");
        assertThat(addition.benefit()).isEqualTo("便于收件人快速阅读");
        assertThat(draft.taskUnderstanding().review().additionCoverage()).hasSize(1);
        assertThat(draft.taskUnderstanding().review().additionCoverage().getFirst().index()).isZero();
        assertThatThrownBy(() -> draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), BODY))
                .isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"forgedSource", "inventedQuote", "forgedOrigin", "missingAnswer", "missingBodyQuote",
            "foreignBodyQuote", "planFieldAtRoot", "planFieldInTask", "questionIdInEnhancement", "nonTextQuote"})
    void invalidEnhancementStopsAfterTheExistingSingleProtocolRepair(String defect) throws Exception {
        var bad = enhancement();
        var task = (ObjectNode) bad.path("task");
        var requirements = (ArrayNode) task.path("requirements");
        var goal = (ObjectNode) requirements.get(0);
        var evidence = (ObjectNode) goal.path("evidence").get(0);
        switch (defect) {
            case "forgedSource" -> evidence.put("sourceId", "actualAnswers.999");
            case "inventedQuote" -> evidence.put("quote", "用户已授权公开发送");
            case "forgedOrigin" -> evidence.put("origin", "BOUND_PLAN_ANSWER");
            case "missingAnswer" -> requirements.remove(3);
            case "missingBodyQuote" -> goal.remove("bodyQuotes");
            case "foreignBodyQuote" -> goal.putArray("bodyQuotes").add("另一份正文");
            case "planFieldAtRoot" -> bad.putArray("questions");
            case "planFieldInTask" -> task.putArray("planImpacts");
            case "questionIdInEnhancement" -> ((ObjectNode) task.path("openDecisions").get(0)).put("questionId", "q_forged");
            case "nonTextQuote" -> goal.putArray("bodyQuotes").add(7);
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("21"));
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThatThrownBy(() -> provider.enhance(input("21"), this::checkDraft))
                    .isInstanceOfSatisfying(ProviderException.class,
                            failure -> assertThat(failure.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        }
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "22", "23", "24", "27"})
    void planGroupsMultipleReasonsUnderOneQuestionAndRetainsUnaskedUnknown(String version) throws Exception {
        expect(1, planning(true), input(version));
        var result = provider.plan(input(version), this::checkPlan);
        assertThat(result.questions()).hasSize(1);
        assertThat(result.taskUnderstanding().openDecisions()).hasSize(3);
        assertThat(result.taskUnderstanding().openDecisions().stream().filter(item -> "q_audience".equals(item.questionId())))
                .hasSize(2);
        assertThat(result.taskUnderstanding().openDecisions().stream().filter(item -> item.questionId().isBlank()))
                .hasSize(1);
        assertThat(result.taskUnderstanding().review().planImpacts()).hasSize(1);
        var impact = result.taskUnderstanding().review().planImpacts().getFirst();
        assertThat(impact.whyAsk()).contains("收件人", "对外公开");
        assertThat(impact.answerDifference()).contains("称呼", "披露范围");
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("taskUnderstanding", "decisionBasis", "unaskedDecisions");
        server.verify();
    }

    @Test
    void zeroQuestionPlanKeepsAnUnaskedUnknownWithoutInventingBodyCoverage() throws Exception {
        expect(1, planning(false), input("21"));
        var result = provider.plan(input("21"), this::checkPlan);
        assertThat(result.questions()).isEmpty();
        assertThat(result.taskUnderstanding().openDecisions()).hasSize(1);
        assertThat(result.taskUnderstanding().review().planImpacts()).isEmpty();
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        server.verify();
    }

    @Test
    void missingPlanSourceIsRepairedWithoutRequestingFinalBodyCoverage() throws Exception {
        var bad = planning(true);
        ((ArrayNode) bad.path("task").path("requirements")).remove(3);
        expect(1, bad, input("21"));
        expect(1, planning(true), input("21"), system -> {
            String instruction = NaturalPromptInstructions.planningInstruction("21");
            assertThat(system).startsWith(instruction);
            // 只检查重试增量，原Plan指导本就说明禁止生成正文；不能把这句禁令误判为要求正文。
            assertThat(system.substring(instruction.length()))
                    .contains("task.requirements", "evidence", "actualAnswers.1")
                    .doesNotContain("正文", "bodyQuotes", "Coverage", "coverage", "尚未确定", "q_place", RAW);
        });
        var observations = new ArrayList<ModelCallObservation>();
        PlanningProviderResponse result;
        try (var ignored = audit(observations)) {
            result = provider.plan(input("21"), this::checkPlan);
        }
        assertThat(result.questions()).hasSize(1);
        assertThat(result.taskUnderstanding().requirements()).hasSize(4);
        assertThat(result.taskUnderstanding().requirements().get(3).evidence().getFirst().sourceId())
                .isEqualTo("actualAnswers.1");
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingBasis", "missingImpact", "bodyCoverage", "modelQuestionId", "missingAnswer", "duplicateQuestion"})
    void malformedPlanCannotBorrowEnhancementOrUnboundDecisionFields(String defect) throws Exception {
        var bad = planning(true);
        var question = (ObjectNode) bad.path("questions").get(0);
        var task = (ObjectNode) bad.path("task");
        switch (defect) {
            case "missingBasis" -> question.remove("decisionBasis");
            case "missingImpact" -> ((ObjectNode) question.path("decisionBasis").get(0)).put("answerDifference", "");
            case "bodyCoverage" -> ((ObjectNode) task.path("requirements").get(0)).putArray("bodyQuotes").add("未生成正文");
            case "modelQuestionId" -> ((ObjectNode) task.path("unaskedDecisions").get(0)).put("questionId", "q_audience");
            case "missingAnswer" -> ((ArrayNode) task.path("requirements")).remove(3);
            case "duplicateQuestion" -> ((ArrayNode) bad.path("questions")).add(question.deepCopy());
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("21"));
        assertThatThrownBy(() -> provider.plan(input("21"), this::checkPlan)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void optionalFrameworkCannotRejectAnOtherwiseValidCandidate(String version) throws Exception {
        var body = enhancement();
        body.remove("framework");
        expect(1, body, input(version));
        assertThat(provider.enhance(input(version), this::checkDraft).framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void unknownTextFrameworkFallsBackWithoutChangingTheBody(String version) throws Exception {
        var body = enhancement();
        body.put("framework", "UNRECOGNIZED_REFERENCE");
        expect(1, body, input(version));
        var draft = provider.enhance(input(version), this::checkDraft);
        assertThat(draft.framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21:missing", "21:unknown", "22:number", "23:number", "24:missing", "24:unknown", "24:number"})
    void frameworkCompatibilityNeverCoercesNonTextMetadata(String example) throws Exception {
        String[] parts = example.split(":");
        var body = enhancement();
        switch (parts[1]) {
            case "missing" -> body.remove("framework");
            case "unknown" -> body.put("framework", "UNRECOGNIZED_REFERENCE");
            case "number" -> body.put("framework", 7);
            default -> throw new IllegalArgumentException(example);
        }
        expect(2, body, input(parts[0]));
        assertThatThrownBy(() -> provider.enhance(input(parts[0]), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void fourthVersionStillRejectsFinalBodyWithNonemptyPlanImpacts() throws Exception {
        var source = enhancement();
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        for (JsonNode item : source.path("task").path("requirements")) {
            requirements.add(new NaturalTaskUnderstanding.Requirement(item.path("text").asText(),
                    List.of(new NaturalTaskUnderstanding.Evidence(item.path("evidence").get(0).path("sourceId").asText(),
                            item.path("evidence").get(0).path("quote").asText())),
                    List.of(NaturalTaskUnderstanding.Dimension.valueOf(item.path("dimensions").get(0).asText()))));
            coverage.add(new NaturalTaskReview.Coverage(coverage.size(), List.of(item.path("bodyQuotes").get(0).asText())));
        }
        var old = new NaturalTaskUnderstanding("写活动通知", "通知正文", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION, coverage, List.of(),
                        List.of(new NaturalTaskReview.PlanImpact("q_audience", "收件人未确定", "不同称呼"))));
        ObjectNode oldWire = json.valueToTree(Map.of("optimizedPrompt", BODY, "framework", "APE", "taskUnderstanding", old));
        expect(2, oldWire, input("4"));
        assertThatThrownBy(() -> provider.enhance(input("4"), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 冻结原件只裁掉认证和隐藏推理，不改正文、来源或覆盖；控制组只在测试中移除已知额外诊断。 */
    @ParameterizedTest
    @ValueSource(strings = {"022", "030", "031"})
    void diagnosticCandidateAcceptsFrozenResponsesWithoutChangingAnyKnownContent(String traceId) throws Exception {
        var fixture = frozenResponse(traceId);
        String raw = fixture.path("responseContent").asText();
        var actualInput = frozenInput(fixture, "24");
        var control = (ObjectNode) json.readTree(raw);
        ((ObjectNode) control.path("task")).remove("022".equals(traceId) ? "task" : "kind");
        expectRaw(1, raw, actualInput, system -> { });
        expect(1, control, frozenInput(fixture, "21"));

        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft actual;
        NaturalPromptDraft expected;
        try (var ignored = audit(observations)) {
            actual = provider.enhance(actualInput, this::checkDraft);
            expected = provider.enhance(frozenInput(fixture, "21"), this::checkDraft);
        }
        assertThat(actual.optimizedPrompt()).isEqualTo(json.readTree(raw).path("optimizedPrompt").asText());
        assertThat(actual.optimizedPrompt()).isEqualTo(expected.optimizedPrompt());
        assertThat(actual.framework()).isEqualTo(expected.framework());
        assertThat(actual.taskUnderstanding()).isEqualTo(expected.taskUnderstanding());
        assertThat(actualInput.taskSources()).isEqualTo(frozenInput(fixture, "21").taskSources());
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 1);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"022", "030", "031"})
    void originalPhaseCandidateStillRejectsThoseSameFrozenUnknownFields(String traceId) throws Exception {
        var fixture = frozenResponse(traceId);
        var actualInput = frozenInput(fixture, "21");
        expectRaw(2, fixture.path("responseContent").asText(), actualInput, system -> { });
        assertThatThrownBy(() -> provider.enhance(actualInput, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 使用与三个原件不同的字段名和标量类型，证明兼容依据是职责边界而非逐题白名单。 */
    @Test
    void harmlessScalarDiagnosticsAreDiscardedInBothPhasesWithoutEnteringReturnedRecords() throws Exception {
        var enhanced = enhancement();
        enhanced.put("diagnosticNote", "ONLY_DIAGNOSTIC_ENHANCE");
        ((ObjectNode) enhanced.path("task")).put("confidence", 0.8);
        ((ObjectNode) enhanced.path("task").path("requirements").get(0)).put("hasContext", false);
        var planned = planning(true);
        planned.put("diagnosticNote", "ONLY_DIAGNOSTIC_PLAN");
        ((ObjectNode) planned.path("task")).put("confidence", 0.7);
        ((ObjectNode) planned.path("questions").get(0)).putNull("reviewerNote");
        expect(1, enhanced, input("24"));
        expect(1, planned, input("24"));
        var draft = provider.enhance(input("24"), this::checkDraft);
        var plan = provider.plan(input("24"), this::checkPlan);
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(plan.taskUnderstanding().requirements()).hasSize(4);
        assertThat(plan.taskUnderstanding().review().planImpacts()).hasSize(1);
        assertThat(json.writeValueAsString(draft) + json.writeValueAsString(plan))
                .doesNotContain("ONLY_DIAGNOSTIC", "diagnosticNote", "confidence", "hasContext", "reviewerNote");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "22", "23"})
    void existingStageReadersRemainStrictForUnrelatedDiagnostics(String version) throws Exception {
        var bad = enhancement();
        bad.put("diagnosticNote", "unrelated");
        expect(2, bad, input(version));
        assertThatThrownBy(() -> provider.enhance(input(version), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 即使值只是标量，阶段职责和服务端归属字段也不能当作无权威诊断吞掉。 */
    @ParameterizedTest
    @ValueSource(strings = {"review", "coverage", "requirementCoverage", "additionCoverage", "planImpacts",
            "questions", "origin", "index", "questionId", "taskSources", "actualAnswers", "strategyVersion",
            "generationStage", "phase", "sourceKind"})
    void diagnosticCompatibilityStillRejectsAuthorityAndStageFields(String field) throws Exception {
        var bad = enhancement();
        ((ObjectNode) bad.path("task")).put(field, "not-authorized");
        expect(2, bad, input("24"));
        assertThatThrownBy(() -> provider.enhance(input("24"), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownObject", "unknownArray", "knownType", "knownEnum", "knownMissing",
            "forgedSource", "missingAnswer", "missingBodyQuote", "foreignBodyQuote", "sourceKind", "sourceOrigin"})
    void diagnosticCompatibilityCannotRepairInvalidKnownSemanticsOrInventAuthority(String defect) throws Exception {
        var bad = enhancement();
        var task = (ObjectNode) bad.path("task");
        var requirements = (ArrayNode) task.path("requirements");
        var first = (ObjectNode) requirements.get(0);
        var evidence = (ObjectNode) first.path("evidence").get(0);
        switch (defect) {
            case "unknownObject" -> task.putObject("diagnosticNote").put("value", "not-scalar");
            case "unknownArray" -> task.putArray("diagnosticNote").add("not-scalar");
            case "knownType" -> task.put("goal", 7);
            case "knownEnum" -> ((ObjectNode) task.path("openDecisions").get(0)).put("kind", "GUESSED");
            case "knownMissing" -> task.remove("requirements");
            case "forgedSource" -> evidence.put("sourceId", "actualAnswers.9");
            case "missingAnswer" -> requirements.remove(3);
            case "missingBodyQuote" -> first.remove("bodyQuotes");
            case "foreignBodyQuote" -> first.putArray("bodyQuotes").add("正文未出现的内容");
            case "sourceKind" -> evidence.put("kind", "USER_CONFIRMATION");
            case "sourceOrigin" -> evidence.put("origin", "USER_CONFIRMATION");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("24"));
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThatThrownBy(() -> provider.enhance(input("24"), this::checkDraft))
                    .isInstanceOfSatisfying(ProviderException.class,
                            failure -> assertThat(failure.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        }
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bodyQuotes", "planImpacts", "missingAnswer", "knownBoolean"})
    void planningDiagnosticsDoNotWeakenPhaseOrActualAnswerRequirements(String defect) throws Exception {
        var bad = planning(true);
        var task = (ObjectNode) bad.path("task");
        switch (defect) {
            case "bodyQuotes" -> ((ObjectNode) task.path("requirements").get(0)).put("bodyQuotes", "not-generated");
            case "planImpacts" -> task.putArray("planImpacts").addObject().put("questionId", "q_audience");
            case "missingAnswer" -> ((ArrayNode) task.path("requirements")).remove(3);
            case "knownBoolean" -> ((ObjectNode) bad.path("questions").get(0)).put("allowCustomAnswer", "true");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("24"));
        assertThatThrownBy(() -> provider.plan(input("24"), this::checkPlan)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void diagnosticCandidateKeepsExactlyTheSameGuidanceAsRequiredFrameworkPhaseCandidate() {
        assertThat(NaturalPromptInstructions.enhancementInstruction("24"))
                .isEqualTo(NaturalPromptInstructions.enhancementInstruction("21"));
        assertThat(NaturalPromptInstructions.planningInstruction("24"))
                .isEqualTo(NaturalPromptInstructions.planningInstruction("21"));
    }

    /** 无独立作品的理解动作与明确报告分别通过真实HTTP适配器；不是靠删除正文来规避新交付。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "项目说明报告"})
    void actionCandidatePreservesOptionalAndExplicitOutcomesWithoutAddingModelStages(String outcome) throws Exception {
        String raw = outcome.isEmpty() ? "先理解这份材料，后续的操作等我说明。"
                : "先理解这份材料，再撰写项目说明报告。";
        var actualInput = new NaturalPromptInput(raw,
                new ContextSnapshot("材料介绍已完成的改造。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(), List.of(), List.of(), null).withStrategyVersion("27");
        var content = json.createObjectNode();
        content.put("optimizedPrompt", raw).put("framework", "APE");
        var task = content.putObject("task");
        task.put("goal", raw).put("deliverable", outcome).put("inputReadiness", "SUFFICIENT");
        var requirement = task.putArray("requirements").addObject();
        requirement.put("text", raw).putArray("dimensions").add("GOAL").add("CONDITION");
        requirement.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", raw);
        requirement.putArray("bodyQuotes").add(raw);
        task.putArray("additions"); task.putArray("openDecisions");
        expect(1, content, actualInput);
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            var draft = provider.enhance(actualInput, this::checkDraft);
            assertThat(draft.optimizedPrompt()).isEqualTo(raw);
            assertThat(draft.taskUnderstanding().deliverable()).isEqualTo(outcome);
            assertThat(draft.taskUnderstanding().additions()).isEmpty();
        }
        assertThat(observations).hasSize(1);
        server.verify();
    }

    /** 候选的语义指导不能放松真实题答来源、正文覆盖或跨阶段字段约束。 */
    @ParameterizedTest
    @ValueSource(strings = {"missingAnswer", "foreignAnchor", "planField"})
    void actionCandidateRetainsSourceCoverageAndStageBoundaries(String defect) throws Exception {
        var bad = enhancement();
        var task = (ObjectNode) bad.path("task");
        var requirements = (ArrayNode) task.path("requirements");
        switch (defect) {
            case "missingAnswer" -> requirements.remove(3);
            case "foreignAnchor" -> ((ObjectNode) requirements.get(0)).putArray("bodyQuotes").add("模型未写入正文的内容");
            case "planField" -> task.putArray("planImpacts");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("27"));
        assertThatThrownBy(() -> provider.enhance(input("27"), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"enhance,28", "plan,28", "enhance,29", "plan,29", "enhance,30", "plan,30"})
    void scopeIsReadOnceAndReusedWithoutReplacingOriginalInput(String stage, String version) throws Exception {
        var input = input(version);
        expect(1, scope(), input);
        var payload = "plan".equals(stage) ? planning(true) : enhancement();
        ((ObjectNode) payload.path("task")).remove(List.of("goal", "deliverable"));
        expect(1, payload, input);
        var observations = new ArrayList<ModelCallObservation>();
        NaturalTaskUnderstanding understood;
        try (var ignored = audit(observations)) {
            understood = "plan".equals(stage) ? provider.plan(input, this::checkPlan).taskUnderstanding()
                    : provider.enhance(input, this::checkDraft).taskUnderstanding();
        }
        assertThat(understood.goal()).isEqualTo("写一条活动通知");
        assertThat(understood.deliverable()).isEqualTo("50字以内的通知正文");
        assertThat(understood.requirements()).hasSize(4);
        assertThat(observations).hasSize(2);
        server.verify();
    }

    @Test
    void unspecifiedResponseStaysEmptyWithoutAnInventedArtifact() throws Exception {
        String raw = "先熟悉材料，后续再按我提出的要求处理。";
        var input = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion("28");
        var scope = json.readTree("""
                {"actions":[{"text":"熟悉材料","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"先熟悉材料"}]},
                  {"text":"按后续要求处理","timing":"CONDITIONAL","condition":"用户提出后续要求","evidence":[{"sourceId":"rawPrompt","quote":"后续再按我提出的要求处理"}]}],
                 "response":{"basis":"UNSPECIFIED","description":"","evidence":[]}}
                """);
        expect(1, (ObjectNode) scope, input);
        var payload = (ObjectNode) json.readTree("""
                {"optimizedPrompt":"先熟悉材料，后续再按我提出的要求处理。","framework":"APE","task":{"inputReadiness":"SUFFICIENT",
                  "requirements":[{"text":"熟悉材料并保留后续条件","dimensions":["GOAL","CONDITION"],
                  "evidence":[{"sourceId":"rawPrompt","quote":"先熟悉材料，后续再按我提出的要求处理。"}],
                  "bodyQuotes":["先熟悉材料，后续再按我提出的要求处理。"]}],"additions":[],"openDecisions":[]}}
                """);
        expect(1, payload, input);
        assertThat(provider.enhance(input, this::checkDraft).taskUnderstanding().deliverable()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "deliverable", "missingAnswer", "foreignAnchor", "planImpacts"})
    void scopeReuseDoesNotAllowReinferenceOrWeakenFinalProtection(String defect) throws Exception {
        var input = input("28");
        expect(1, scope(), input);
        var payload = enhancement();
        var task = (ObjectNode) payload.path("task");
        task.remove(List.of("goal", "deliverable"));
        switch (defect) {
            case "goal", "deliverable" -> task.put(defect, "另行推断的目标");
            case "missingAnswer" -> ((ArrayNode) task.path("requirements")).remove(3);
            case "foreignAnchor" -> ((ObjectNode) task.path("requirements").get(0)).putArray("bodyQuotes").add("另一份正文");
            case "planImpacts" -> task.putArray("planImpacts");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, payload, input);
        assertThatThrownBy(() -> provider.enhance(input, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateRoot", "duplicateNested", "trailingObject"})
    void ambiguousJsonCannotEraseQuestionsOrRequirementEvidence(String defect) throws Exception {
        String content = json.writeValueAsString(planning(true));
        content = switch (defect) {
            case "duplicateRoot" -> content.substring(0, content.length() - 1) + ",\"questions\":[]}";
            case "duplicateNested" -> content.replace("\"sourceId\":\"rawPrompt\"", "\"sourceId\":\"forged\",\"sourceId\":\"rawPrompt\"");
            case "trailingObject" -> content + "{}";
            default -> throw new IllegalArgumentException(defect);
        };
        expectRaw(2, content, input("24"), system -> { });
        assertThatThrownBy(() -> provider.plan(input("24"), this::checkPlan)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private ObjectNode scope() throws Exception {
        return (ObjectNode) json.readTree("""
                {"actions":[{"text":"写一条活动通知","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"请写一条活动通知"}]}],
                 "response":{"basis":"EXPLICIT","description":"50字以内的通知正文","evidence":[{"sourceId":"rawPrompt","quote":"50字以内，只输出正文"}]}}
                """);
    }

    @Test
    void noIndependentResponseUsesOnlyTheScopeCallAndCopiesActualRequest() throws Exception {
        String raw = "先理解这份材料，之后按我的具体要求处理。";
        var input = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion("29");
        var response = (ObjectNode) json.readTree("""
                {"actions":[{"text":"理解材料","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"先理解这份材料"}]}],
                 "response":{"basis":"UNSPECIFIED","description":"","evidence":[]}}
                """);
        expect(1, response, input);
        assertThat(provider.enhance(input, this::checkDraft).optimizedPrompt()).isEqualTo(raw);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "31", "32", "33"})
    void inferredReadinessFeedbackCannotStartANewWritingStage(String version) throws Exception {
        String raw = "先吃透材料中各状态的关系，后面我会逐项给你问题。";
        var input = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion(version);
        var response = (ObjectNode) json.readTree("""
                {"actions":[{"text":"理解状态关系","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"先吃透材料中各状态的关系"}]}],
                 "response":{"basis":"INHERENT","description":"确认已理解并等待问题","evidence":[{"sourceId":"rawPrompt","quote":"后面我会逐项给你问题"}]}}
                """);
        expect(1, response, input);
        var draft = provider.enhance(input, this::checkDraft);
        assertThat(draft.optimizedPrompt()).isEqualTo(raw);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        server.verify();
    }

    @Test
    void guardedFourthSendsTheExactFrozenFourthWritingInputAfterScopePreparation() throws Exception {
        var input = input("31");
        expect(1, scope(), input);
        var normalized = json.treeToValue(enhancement(), NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false));
        var frozen = json.createObjectNode().put("optimizedPrompt", BODY).put("framework", "APE");
        frozen.set("taskUnderstanding", json.valueToTree(normalized.taskUnderstanding()));
        expect(1, frozen, input("4"), system -> assertThat(system).isEqualTo(NaturalPromptInstructions.ENHANCE));
        var draft = provider.enhance(input, this::checkDraft);
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        server.verify();
    }

    @Test
    void relationalCompletionKeepsFullInputAndFourthCoverageAfterTheSameScopeGuard() throws Exception {
        var input = input("32");
        expect(1, scope(), input);
        var normalized = json.treeToValue(enhancement(), NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false));
        var response = json.createObjectNode().put("optimizedPrompt", BODY).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(normalized.taskUnderstanding()));
        expect(1, response, input, system -> assertThat(system).isEqualTo(NaturalPromptRelationalInstructions.ENHANCE));
        var draft = provider.enhance(input, this::checkDraft);
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    @Test
    void supplementCandidateChecksSourceAndFinalBodyWithoutAddingAnotherModelStage() throws Exception {
        var input = input("33");
        expect(1, scope(), input);
        var response = fourthResponse(input);
        String addition = "请按已知条件选择通知措辞，未知时间继续保留待定。";
        response.put("optimizedPrompt", BODY + "\n" + addition);
        ((ArrayNode) response.path("taskUnderstanding").path("additions")).addObject()
                .put("text", addition).put("benefit", "保留条件到表达的关系").put("gap", "条件如何影响通知措辞需明确");
        ((ArrayNode) response.path("taskUnderstanding").path("review").path("additionCoverage"))
                .addObject().put("index", 0).putArray("quotes").add(addition);
        expect(1, response, input, system -> assertThat(system).isEqualTo(NaturalPromptRelationalInstructions.ENHANCE));
        var checked = new ArrayList<NaturalPromptDraft>();
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = audit(observations)) {
            result = provider.enhance(input, draft -> { checked.add(draft); return checkDraft(draft); });
        }
        assertThat(checked).hasSize(2);
        assertThat(checked.getFirst().optimizedPrompt()).isEqualTo(BODY + "\n" + addition);
        assertThat(result.optimizedPrompt()).startsWith(RAW).contains(addition,
                "用户实际题答：\n活动时间？\n尚未确定。", "用户实际题答：\n活动地点？\n尚未确定。")
                .doesNotContain(BODY);
        assertThat(result.taskUnderstanding().requirements()).isEqualTo(checked.getFirst().taskUnderstanding().requirements());
        assertThat(result.taskUnderstanding().openDecisions()).isEqualTo(checked.getFirst().taskUnderstanding().openDecisions());
        assertThat(result.taskUnderstanding().goal()).isEqualTo("写活动通知");
        assertThat(result.taskUnderstanding().deliverable()).isEqualTo("通知正文");
        assertThat(result.taskUnderstanding().review().additionCoverage()).hasSize(1);
        assertThat(observations).hasSize(2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"32", "33"})
    void relationalFrameworkProtocolErrorsReceiveOnlyTheExistingBoundedRepair(String version) throws Exception {
        var input = input(version);
        expect(1, scope(), input);
        var bad = fourthResponse(input).put("framework", "UNKNOWN_FRAMEWORK");
        expect(1, bad, input);
        expect(1, fourthResponse(input), input, system -> assertThat(system)
                .startsWith(NaturalPromptRelationalInstructions.ENHANCE).contains("上次响应未满足协议或必要保护"));
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            var draft = provider.enhance(input, this::checkDraft);
            assertThat(draft.framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        }
        assertThat(observations).hasSize(3);
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"32", "33"})
    void relationalFrameworkRemainsRejectedAfterTwoInvalidAttempts(String version) throws Exception {
        var input = input(version);
        expect(1, scope(), input);
        expect(2, fourthResponse(input).put("framework", "UNKNOWN_FRAMEWORK"), input);
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThatThrownBy(() -> provider.enhance(input, this::checkDraft))
                    .isInstanceOfSatisfying(ProviderException.class,
                            failure -> assertThat(failure.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        }
        assertThat(observations).hasSize(3);
        server.verify();
    }

    @Test
    void supplementCannotUseRenderingToBypassInvalidSourceDraftCoverage() throws Exception {
        var input = input("33");
        expect(1, scope(), input);
        var bad = fourthResponse(input);
        ((ObjectNode) bad.path("taskUnderstanding").path("review").path("requirementCoverage").get(0))
                .putArray("quotes").add("并未存在于首稿的片段");
        expect(1, bad, input);
        expect(1, fourthResponse(input), input);
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThat(provider.enhance(input, this::checkDraft).optimizedPrompt()).startsWith(RAW);
        }
        assertThat(observations).hasSize(3);
        server.verify();
    }

    /** 32/33仍读取冻结4形状，不用新阶段DTO替换其来源和覆盖协议。 */
    private ObjectNode fourthResponse(NaturalPromptInput input) throws Exception {
        var normalized = json.treeToValue(enhancement(), NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false));
        var response = json.createObjectNode().put("optimizedPrompt", BODY).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(normalized.taskUnderstanding()));
        return response;
    }

    /** 三份原响应按UTF-8内容哈希冻结，运行期不依赖本机tmp或评测目录。 */
    private JsonNode frozenResponse(String traceId) throws Exception {
        var expected = Map.of("022", "4528460670d70177e20d58de2e554feb18c1c273264047b4b1cdda21d014f1a6",
                "030", "0f4671190ecfe8a38135324b368049709c1e84f1c648fc993e22782886123996",
                "031", "e0c0300c234e48ba96504318931613973e36e37bd1734e30472c1ba21690e604");
        try (var resource = getClass().getResourceAsStream("/provider/natural-phase/trace-" + traceId + ".json")) {
            assertThat(resource).isNotNull();
            var fixture = json.readTree(resource);
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fixture.path("responseContent").asText().getBytes(StandardCharsets.UTF_8)));
            assertThat(digest).isEqualTo(expected.get(traceId)).isEqualTo(fixture.path("responseContentSha256").asText());
            return fixture;
        }
    }

    /** 复用完整合成任务；来源目录重新由服务端计算，模型选择仅替换为本地HTTP夹具。 */
    private NaturalPromptInput frozenInput(JsonNode fixture, String version) throws Exception {
        var original = (ObjectNode) fixture.path("input").deepCopy();
        original.remove("taskSources");
        original.putNull("modelId");
        var input = json.treeToValue(original, NaturalPromptInput.class).withStrategyVersion(version);
        JsonNode actualSources = json.valueToTree(input.taskSources());
        assertThat(actualSources).isEqualTo(fixture.path("input").path("taskSources"));
        return input;
    }

    /** 同样的答案来自不同实际题干；不能去重为一个确认，助手建议也不能改成确认来源。 */
    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput(RAW,
                new ContextSnapshot("本次资料未提供场地容量。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q_time", "活动时间？", "尚未确定。"), new PlanAnswer("q_place", "活动地点？", "尚未确定。")),
                List.of(new ConversationMessage("assistant", "建议周五开始，用户尚未确认。")), List.of(), null)
                .withStrategyVersion(version);
    }

    /** 与业务回调相同，在规范化后再次验证最终正文；正文组装后的保护仍由既有服务测试覆盖。 */
    private NaturalPromptDraft checkDraft(NaturalPromptDraft draft) {
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
        return draft;
    }

    private PlanningProviderResponse checkPlan(PlanningProviderResponse plan) {
        plan.taskUnderstanding().review().validatePlan(plan.questions().stream().map(question -> question.id()).collect(java.util.stream.Collectors.toSet()));
        return plan;
    }

    /** 请求体核对复用原始输入，不让协议简化把实际题答或来源身份移交给模型。 */
    private void expect(int count, ObjectNode content, NaturalPromptInput input) throws Exception {
        expect(count, content, input, system -> { });
    }

    /** 除完整输入外可核对本次system增量，避免修复提示跨阶段或泄露实际题答。 */
    private void expect(int count, ObjectNode content, NaturalPromptInput input, Consumer<String> checkSystem) throws Exception {
        expectRaw(count, json.writeValueAsString(content), input, checkSystem);
    }

    /** 原件字符串直接放入模拟上游信封，不通过重序列化修正原响应。 */
    private void expectRaw(int count, String content, NaturalPromptInput input, Consumer<String> checkSystem) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", content))), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
        server.expect(ExpectedCount.times(count), requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            checkSystem.accept(sent.path("messages").get(0).path("content").asText());
            String user = sent.path("messages").get(1).path("content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            if ("4".equals(input.strategyVersion())) assertThat(payload).isEqualTo(json.valueToTree(input));
            if (NaturalPromptScopedInstructions.supports(input.strategyVersion())) {
                boolean preparing = sent.path("messages").get(0).path("content").asText()
                        .startsWith(NaturalPromptScopedInstructions.PREPARE);
                boolean writing32 = NaturalPromptRelationalInstructions.supports(input.strategyVersion()) && !preparing
                        && sent.path("messages").get(0).path("content").asText().startsWith(NaturalPromptRelationalInstructions.ENHANCE);
                assertThat(payload.has("taskScope")).isEqualTo(!preparing && !writing32);
                if (!preparing && !writing32) assertThat(payload.path("taskScope").has("actions")).isTrue();
            }
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("context")).isEqualTo(json.valueToTree(input.context()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("conversation")).isEqualTo(json.valueToTree(input.conversation()));
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.path("strategyVersion").asText()).isEqualTo(input.strategyVersion());
            assertThat(payload.has("completionSuggestions")).isFalse();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private ModelCallAuditContext.Scope audit(List<ModelCallObservation> observations) {
        return ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 2123), (tenant, fact) -> observations.add(fact)));
    }

    /** 固定任务原句与正文片段，分别表达真实确认与原定目标；测试不借同一片段伪装不同信息状态。 */
    private ObjectNode enhancement() throws Exception {
        return (ObjectNode) json.readTree("""
                {"optimizedPrompt":"请写一条50字以内的活动通知，只输出正文；时间待定，地点待定。","framework":"APE",
                 "task":{"goal":"写活动通知","deliverable":"通知正文","inputReadiness":"NEEDS_COMPLETION",
                  "requirements":[
                   {"text":"交付通知","dimensions":["GOAL"],"evidence":[{"sourceId":"rawPrompt","quote":"请写一条活动通知"}],"bodyQuotes":["活动通知"]},
                   {"text":"篇幅与格式","dimensions":["FORMAT","LENGTH"],"evidence":[{"sourceId":"rawPrompt","quote":"50字以内，只输出正文"}],"bodyQuotes":["50字以内的活动通知，只输出正文"]},
                   {"text":"时间未知","dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.0","quote":"尚未确定。"}],"bodyQuotes":["时间待定"]},
                   {"text":"地点未知","dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.1","quote":"尚未确定。"}],"bodyQuotes":["地点待定"]}],
                  "additions":[],"openDecisions":[{"issue":"时间未知","impact":"通知保留待定","kind":"USER_INFORMATION"}]}}
                """);
    }

    /** 一个问题可以有多个实际交付依据；未提问的未知不冒充实际问题或答案。 */
    private ObjectNode planning(boolean withQuestion) throws Exception {
        var original = enhancement();
        var task = (ObjectNode) original.path("task").deepCopy();
        task.remove(List.of("additions", "openDecisions"));
        task.path("requirements").forEach(item -> ((ObjectNode) item).remove("bodyQuotes"));
        task.set("unaskedDecisions", json.readTree("""
                [{"issue":"场地容量未提供","impact":"不声称已核对容量，其余内容继续起草","kind":"USER_INFORMATION"}]
                """));
        var plan = json.createObjectNode().put("summary", "先确认实际收件人，其余措辞由执行者设计");
        plan.set("task", task);
        plan.set("questions", json.readTree(withQuestion ? """
                [{"id":"q_audience","question":"面向哪些收件人，是否对外公开？","hint":"影响称呼及可披露范围",
                  "type":"FREE_TEXT","options":[],"examples":[],"allowCustomAnswer":true,
                  "decisionBasis":[
                   {"issue":"收件人未确定","kind":"USER_INFORMATION","whyAsk":"收件人属于用户实际情况","answerDifference":"改变称呼"},
                   {"issue":"对外公开未确定","kind":"USER_INFORMATION","whyAsk":"对外公开需要用户确认","answerDifference":"改变披露范围"}]}]
                """ : "[]"));
        return plan;
    }
}
