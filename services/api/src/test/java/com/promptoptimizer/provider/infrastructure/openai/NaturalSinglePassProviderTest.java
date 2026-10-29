package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 54复用24协议的一次生成路径；合成响应只证明接线及保护，不证明模型理解或作品收益。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalSinglePassProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final Path LEGACY = Path.of("src/test/resources/natural-single-pass/frozen-pre54.json");
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture"); properties.setModel("fixture"); properties.setModels(List.of("fixture"));
        properties.setMaxTokens(8192); properties.setTemperature(0.2);
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void legacyRequestsAndGuidanceKeepCapturedBytes() throws Exception {
        var frozen = json.createObjectNode(); var guidance = frozen.putObject("guidance");
        var versions = new ArrayList<>(List.of("24", "27"));
        for (int number = 31; number <= 53; number++) versions.add(Integer.toString(number));
        for (String version : versions) {
            var item = guidance.putObject(version);
            item.put("enhanceSha256", hash(NaturalPromptInstructions.enhancementInstruction(version)));
            item.put("planSha256", hash(NaturalPromptInstructions.planningInstruction(version)));
        }
        for (String version : List.of("24", "27", "31", "47", "49", "50", "51", "52", "53")) {
            for (boolean answered : List.of(false, true)) {
                var input = input("请整理方案，不修改文件。", version, answered);
                String body = "请整理方案，不修改文件；未确定的预算继续保持未知。";
                boolean phase = Set.of("24", "27").contains(version);
                if (!phase) {
                    var prepared = requirements(input);
                    if (!version.equals("53")) prepared.setAll(scope(input));
                    expect(Set.of("31", "47").contains(version) ? scope(input) : prepared);
                }
                expect(phase ? writing(input, body) : Set.of("31", "47").contains(version)
                        ? canonicalWriting(input, body) : referencedWriting(input, body));
            }
        }
        var oldPlan = input("以下只是背景，尚未指定处理任务。", "52", true);
        expect(descriptiveScope(oldPlan)); expect(plan(oldPlan, true)); expect(plan(oldPlan, false));
        for (String version : List.of("24", "27", "31", "47", "49", "50", "51", "52", "53")) {
            for (boolean answered : List.of(false, true)) {
                var input = input("请整理方案，不修改文件。", version, answered);
                provider.enhance(input, value -> checked(value, input));
            }
        }
        provider.plan(oldPlan, value -> {
            value.taskUnderstanding().resolve(oldPlan).review().validatePlan(Set.of()); return value;
        });
        frozen.set("requests", json.valueToTree(requests));
        if (Boolean.getBoolean("capture54Legacy")) {
            Files.createDirectories(LEGACY.getParent());
            Files.writeString(LEGACY, json.writerWithDefaultPrettyPrinter().writeValueAsString(frozen), StandardOpenOption.CREATE_NEW);
        } else {
            var original = json.readTree(Files.readString(LEGACY));
            assertThat(guidance).isEqualTo(original.path("guidance"));
            assertThat(requests).hasSize(original.path("requests").size());
            for (int index = 0; index < requests.size(); index++) assertLimitedMessageKeyOrder(requests.get(index), original.path("requests").get(index).asText());
        }
        server.verify();
    }

    @Test
    void oneGenerationPreservesCompleteInputAndRecordsOnlyBodyStage() throws Exception {
        var input = input("请整理方案，不修改文件。", "54", true);
        String body = "请整理方案，不修改文件；预算保持未知，建议只作为待评估意见。";
        var response = writing(input, body); response.put("unlistedScalarDiagnostic", "discarded");
        expect(response);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages().stream().map(Enum::name)).containsExactly("BODY_GENERATION");
        assertThat(requests).hasSize(1); assertCompleteInput(requests.getFirst(), input);
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        server.verify();
    }

    @Test
    void missingActualAnswerCanRepairInTheSameSingleStageWithoutPreparation() throws Exception {
        var input = input("请整理方案，不修改文件。", "54", true);
        String body = "请整理方案，不修改文件；预算保持未知。";
        var missing = writing(input, body); missing.withObject("task").withArray("requirements").remove(3);
        expect(missing); expect(writing(input, body));
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.completedStages().stream().map(Enum::name)).containsExactly("BODY_GENERATION");
        assertThat(requests).hasSize(2);
        assertThat(system(requests.get(1))).contains("actualAnswers.0").doesNotContain("requirementChanges", "三个review数组");
        assertThat(user(requests.get(1))).isEqualTo(user(requests.getFirst()));
        for (String request : requests) assertCompleteInput(request, input);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"readWait", "future", "background", "sufficient", "knownCause", "unknown"})
    void canExpressExistingTaskStatesWithoutCreatingAnIndependentDeliverable(String variant) throws Exception {
        String raw = switch (variant) {
            case "readWait" -> "只阅读现有材料后等待，不修改文件，不交付说明。";
            case "future" -> "等我提供清单后再分析，现在只记住这些条件。";
            case "background" -> "以下只是背景，尚未指定处理任务。";
            case "knownCause" -> "记录明确写明中断由断电造成；说明影响，不扩大为所有中断的原因。";
            default -> "请整理方案，不修改文件。";
        };
        var input = input(raw, "54", variant.equals("unknown"));
        var response = writing(input, raw);
        if (variant.equals("background")) ((ObjectNode) response.path("task").path("requirements").get(0)).putArray("dimensions").add("OBJECT");
        expect(response);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(raw);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        if (variant.equals("background")) {
            assertThat(draft.taskUnderstanding().goal()).isEqualTo(raw);
            assertThat(draft.taskUnderstanding().requirements()).noneMatch(item -> item.dimensions().contains(NaturalTaskUnderstanding.Dimension.GOAL));
        }
        assertThat(requests).hasSize(1); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingAnswer", "forgedQuote", "forgedOrigin", "materialGoal", "assistantGoal", "foreignBodyQuote",
            "missingBodyQuotes", "wrongKnownType", "unknownObject", "crossPhase", "duplicateKey", "unsafeBody", "assembledQuote"})
    void existingProtocolAndSourceProtectionsStillRejectInvalidDeliveries(String defect) throws Exception {
        var input = input("请整理方案，不修改文件。", "54", true);
        var response = writing(input, "请整理方案，不修改文件；预算未知。");
        var required = response.withObject("task").withArray("requirements");
        switch (defect) {
            case "missingAnswer" -> required.remove(3);
            case "forgedQuote" -> ((ObjectNode) required.get(0).path("evidence").get(0)).put("quote", "不存在的原句");
            case "forgedOrigin" -> ((ObjectNode) required.get(0).path("evidence").get(0)).put("origin", "ORIGINAL_REQUEST");
            case "materialGoal", "assistantGoal" -> ((ObjectNode) required.get(defect.equals("materialGoal") ? 1 : 2)).putArray("dimensions").add("GOAL");
            case "foreignBodyQuote" -> ((ObjectNode) required.get(0)).putArray("bodyQuotes").add("正文中不存在的句子");
            case "missingBodyQuotes" -> ((ObjectNode) required.get(0)).remove("bodyQuotes");
            case "wrongKnownType" -> ((ObjectNode) required.get(0)).put("text", 1);
            case "unknownObject" -> response.putObject("unlistedDiagnostic").put("text", "object");
            case "crossPhase" -> response.putArray("questions");
            case "unsafeBody" -> response.put("optimizedPrompt", "sk-" + "A".repeat(40));
            case "assembledQuote" -> {
                response.put("optimizedPrompt", "请整理方案，不修改文件；预算未知。\n不得删除文件。\n  不得删除文件。");
                ((ObjectNode) required.get(0)).putArray("bodyQuotes").add("  不得删除文件。");
            }
            default -> { }
        }
        String content = defect.equals("duplicateKey") ? "{\"optimizedPrompt\":\"x\",\"optimizedPrompt\":\"y\"}" : json.writeValueAsString(response);
        expectContent(content); expectContent(content);
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(2);
        for (String request : requests) assertCompleteInput(request, input);
        server.verify();
    }

    @Test
    void planKeeps52ScopeCanonicalReaderAndMissingSourceRepairBytes() throws Exception {
        var oldInput = input("以下只是背景，尚未指定处理任务。", "52", true);
        var input = oldInput.withStrategyVersion("54");
        expect(descriptiveScope(input)); expect(plan(input, true)); expect(plan(input, false));
        provider.plan(input, value -> {
            value.taskUnderstanding().resolve(input).review().validatePlan(Set.of()); return value;
        });
        var old = json.readTree(Files.readString(LEGACY)).path("requests");
        assertThat(requests).hasSize(3);
        for (int index = 0; index < 3; index++) {
            // 仅替换服务端版本标记；system、user其余字节、数组及完整题答都不能重排或改写。
            String request = requests.get(index).replace("\\\"strategyVersion\\\":\\\"54\\\"", "\\\"strategyVersion\\\":\\\"52\\\"");
            assertLimitedMessageKeyOrder(request, old.get(old.size() - 3 + index).asText());
        }
        assertThat(system(requests.get(2))).contains("任务理解的evidence").doesNotContain("Plan的task.requirements", "三个review数组");
        server.verify();
    }

    /** 只保存合成来源：原用户目标、材料意见、助手意见及两个同文真实回答必须分别保留。 */
    private NaturalPromptInput input(String raw, String version, boolean answered) {
        var context = new ContextSnapshot("现有行为待评估；有人建议立即清空记录，原因未确认。", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", List.of(), List.of(), List.of(), "v3");
        return new NaturalPromptInput(raw, context,
                answered ? List.of(new PlanAnswer("budget", "预算？", "未知"), new PlanAnswer("start", "开始时间？", "未知")) : List.of(),
                List.of(new ConversationMessage("assistant", "立即实施并清空记录。")), List.of("不得删除文件。"), null).withStrategyVersion(version);
    }

    private ObjectNode requirements(NaturalPromptInput input) {
        var response = json.createObjectNode(); var list = response.putArray("requirements");
        for (var source : input.taskSources()) {
            var item = list.addObject().put("text", source.text());
            item.putArray("dimensions").add(source.id().equals("rawPrompt") ? "GOAL" : "CONDITION");
            item.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text());
        }
        return response;
    }

    private ObjectNode writing(NaturalPromptInput input, String body) {
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        var task = response.putObject("task").put("goal", input.rawPrompt()).put("deliverable", "")
                .put("inputReadiness", input.actualAnswers().isEmpty() ? "SUFFICIENT" : "NEEDS_COMPLETION");
        task.set("requirements", requirements(input).path("requirements"));
        for (var item : task.withArray("requirements")) ((ObjectNode) item).putArray("bodyQuotes").add(body);
        task.putArray("additions"); var decisions = task.putArray("openDecisions");
        if (!input.actualAnswers().isEmpty()) decisions.addObject().put("issue", "预算和开始时间仍未知").put("impact", "依赖信息的部分待定").put("kind", "USER_INFORMATION");
        return response;
    }

    private ObjectNode canonicalWriting(NaturalPromptInput input, String body) throws Exception {
        var phase = json.treeToValue(writing(input, body), NaturalPromptPhasePayloads.Enhancement.class);
        var draft = phase.toDraft(input.withStrategyVersion("24"), new com.promptoptimizer.enhancement.domain.ProviderMetadata("legacy", "fixture", false));
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(draft.taskUnderstanding())); return response;
    }

    private ObjectNode referencedWriting(NaturalPromptInput input, String body) {
        var response = writing(input, body); var task = (ObjectNode) response.remove("task");
        response.put("goal", input.rawPrompt()).put("deliverable", "").put("inputReadiness", task.path("inputReadiness").asText());
        var coverage = response.putArray("coverage");
        for (int index = 0; index < input.taskSources().size(); index++) coverage.addObject().put("reqId", "r" + index).putArray("bodyQuotes").add(body);
        response.putArray("requirementChanges"); response.set("additions", task.get("additions")); response.set("openDecisions", task.get("openDecisions")); return response;
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var value = json.createObjectNode(); var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        value.set("actions", json.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence))));
        value.set("response", json.valueToTree(Map.of("basis", "EXPLICIT", "description", "原定方案", "evidence", evidence))); return value;
    }

    private ObjectNode descriptiveScope(NaturalPromptInput input) {
        var scope = scope(input); ((ObjectNode) scope.path("actions").get(0)).put("timing", "DESCRIPTIVE");
        ((ObjectNode) scope.get("response")).put("basis", "UNSPECIFIED").put("description", "").putArray("evidence"); return scope;
    }

    private ObjectNode plan(NaturalPromptInput input, boolean missingAnswer) {
        var required = input.taskSources().stream().filter(source -> !missingAnswer || !source.id().equals("actualAnswers.0"))
                .map(source -> new NaturalTaskUnderstanding.Requirement(source.text(), List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())),
                        List.of(NaturalTaskUnderstanding.Dimension.CONDITION))).toList();
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "", required, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        var value = json.createObjectNode().put("summary", "仅保留背景和未知，不新增当前任务。");
        value.putArray("questions"); value.set("taskUnderstanding", json.valueToTree(task)); return value;
    }

    /** 延用实际应用校验；原稿与权限组装后均核验，模型自报状态不作为成功依据。 */
    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        try {
            var method = NaturalEnhancementService.class.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new NaturalEnhancementService(null, null, null, null, null, null), draft, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, draft.optimizedPrompt().strip()); task.review().validateEnhancement(task, assembled);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime; throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return draft;
    }

    /** 现代可见性说明是唯一派生输入，完整原字段和实际来源目录均逐字保留。 */
    private void assertCompleteInput(String raw, NaturalPromptInput input) throws Exception {
        var payload = (ObjectNode) json.readTree(user(raw).substring(user(raw).indexOf('{')));
        assertThat(payload.has("taskScope") || payload.has("preparedRequirements") || payload.has("boundQuestionHints") || payload.has("protocolRepair")).isFalse();
        assertThat(payload.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
        payload.remove("inputBoundary");
        assertThat(json.writeValueAsString(payload)).isEqualTo(json.writeValueAsString(input));
    }

    /** Map.of的role/content两键顺序可跨JVM变化；只枚举这两个键，不规范化任何消息内容或内部JSON。 */
    private void assertLimitedMessageKeyOrder(String actual, String original) throws Exception {
        var variants = new ArrayList<String>(); variants.add(actual);
        for (var keys : List.of(List.of("role", "content"), List.of("content", "role"))) {
            var tree = (ObjectNode) json.readTree(actual);
            for (var message : tree.withArray("messages")) {
                assertThat(message.size()).isEqualTo(2); assertThat(message.has("role") && message.has("content")).isTrue();
                var saved = message.deepCopy(); var ordered = (ObjectNode) message; ordered.removeAll();
                for (String key : keys) ordered.set(key, saved.get(key));
            }
            variants.add(json.writeValueAsString(tree));
        }
        assertThat(variants).contains(original);
    }

    private String hash(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private String system(String raw) throws Exception { return json.readTree(raw).path("messages").get(0).path("content").asText(); }
    private String user(String raw) throws Exception { return json.readTree(raw).path("messages").get(1).path("content").asText(); }
    private void expect(ObjectNode response) throws Exception { expectContent(json.writeValueAsString(response)); }
    private void expectContent(String content) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> requests.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
