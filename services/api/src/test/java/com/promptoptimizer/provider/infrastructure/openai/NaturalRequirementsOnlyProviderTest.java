package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * 53准备只登记要求；以真实Provider HTTP边界验证阶段接线及既有保护，不将合成理解视为模型语义收益。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalRequirementsOnlyProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> requestBytes = new ArrayList<>();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture"); properties.setModel("fixture"); properties.setModels(List.of("fixture", "deepseek:deepseek-flash"));
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void requirementsOnlyPreparationReachesOneWriterWithoutInventingScope() throws Exception {
        var input = input("请解释缓存刷新机制。", List.of(), "53");
        expect(requirements(input)); expect(writing(input.rawPrompt(), 1).put("unlistedScalarDiagnostic", "discarded"));
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(input.rawPrompt());
        assertThat(draft.completedStages().stream().map(Enum::name)).containsExactly("REQUIREMENT_PREPARATION", "BODY_GENERATION");
        assertThat(requests).hasSize(2);
        assertThat(system(requests.get(1))).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.ENHANCE);
        for (var request : requests) {
            var user = payload(request);
            assertThat(user.has("taskScope")).isFalse(); assertThat(user.has("boundQuestionHints")).isFalse();
            assertThat(user.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
        }
        assertThat(payload(requests.get(0)).has("preparedRequirements")).isFalse();
        assertThat(payload(requests.get(1)).path("preparedRequirements").get(0).path("reqId").asText()).isEqualTo("r0");
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31"); server.verify();
    }

    @Test
    void missingActualAnswerPreparationRepairsAndThenWritesWithTheSameCompleteInput() throws Exception {
        var input = protectedInput(List.of("不得删除文件。"));
        var complete = requirements(input); var missing = complete.deepCopy();
        missing.withArray("requirements").remove(3);
        String body = "请整理方案，不修改文件；预算保持未知。";
        var writer = writing(body, 4).put("goal", input.rawPrompt()).put("inputReadiness", "NEEDS_COMPLETION");
        writer.withArray("openDecisions").addObject().put("issue", "预算未知").put("impact", "依赖预算的部分仍待确定").put("kind", "USER_INFORMATION");
        // 第一稿确实缺实际题答；只由实际MissingSources分支触发一次准备修复，不手动重试Provider。
        expect(missing); expect(complete); expect(writer);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages().stream().map(Enum::name)).containsExactly("REQUIREMENT_PREPARATION", "BODY_GENERATION");
        assertThat(requests).hasSize(3);
        assertThat(system(requests.get(0))).isEqualTo(NaturalPromptRequirementsOnlyInstructions.PREPARE);
        assertThat(system(requests.get(1))).contains("准备requirements.evidence", "actualAnswers.0").doesNotContain("三个review数组");
        assertThat(requests.get(1).path("messages").get(1).path("content").asText())
                .isEqualTo(requests.get(0).path("messages").get(1).path("content").asText());
        assertThat(system(requests.get(2))).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.ENHANCE);
        for (var request : requests) assertCompleteOriginalInput(request, input);
        assertThat(payload(requests.get(0)).has("preparedRequirements")).isFalse();
        assertThat(payload(requests.get(1)).has("preparedRequirements")).isFalse();
        assertThat(payload(requests.get(2)).path("preparedRequirements").size()).isEqualTo(4);
        assertThat(draft.taskUnderstanding().requirements().stream().flatMap(item -> item.evidence().stream())
                .filter(item -> item.sourceId().equals("actualAnswers.0")).map(NaturalTaskUnderstanding.Evidence::quote))
                .containsExactly(input.taskSources().get(3).text());
        server.verify();
    }

    @Test
    void ordinarySuccessPreservesNonemptyContextAnswersPermissionsAndSourcesAcrossBothPhases() throws Exception {
        var input = protectedInput(List.of("不得删除文件。"));
        String body = "请整理方案，不修改文件；预算保持未知。";
        var writer = writing(body, 4).put("goal", input.rawPrompt()).put("inputReadiness", "NEEDS_COMPLETION");
        writer.withArray("openDecisions").addObject().put("issue", "预算未知").put("impact", "依赖预算的部分仍待确定").put("kind", "USER_INFORMATION");
        expect(requirements(input)); expect(writer);
        assertThat(provider.enhance(input, value -> checked(value, input)).optimizedPrompt()).isEqualTo(body);
        assertThat(requests).hasSize(2);
        assertThat(input.context()).isNotNull(); assertThat(input.actualAnswers()).isNotEmpty();
        assertThat(input.mandatoryRules()).isNotEmpty(); assertThat(input.taskSources()).hasSize(4);
        for (var request : requests) assertCompleteOriginalInput(request, input);
        assertThat(payload(requests.get(0)).has("preparedRequirements")).isFalse();
        assertThat(payload(requests.get(1)).path("preparedRequirements").size()).isEqualTo(4);
        server.verify();
    }

    @Test
    void descriptiveBackgroundWithoutAUserTaskDoesNotRequireAnInventedGoal() throws Exception {
        var oldInput = input("这是已有记录的背景，尚未提出处理任务。", List.of(), "51");
        var candidate = oldInput.withStrategyVersion("53");
        var only = requirements(candidate);
        ((ObjectNode) only.path("requirements").get(0)).putArray("dimensions").add("OBJECT");
        var oldPrepare = only.deepCopy(); var oldScope = scope(oldInput);
        ((ObjectNode) oldScope.path("actions").get(0)).put("timing", "DESCRIPTIVE");
        ((ObjectNode) oldScope.path("response")).put("basis", "UNSPECIFIED").put("description", "").putArray("evidence");
        oldPrepare.setAll(oldScope);
        expect(oldPrepare); expect(writing(oldInput.rawPrompt(), 1));
        expect(only); expect(writing(candidate.rawPrompt(), 1));
        assertThat(provider.enhance(oldInput, value -> checked(value, oldInput)).optimizedPrompt()).isEqualTo(oldInput.rawPrompt());
        var draft = provider.enhance(candidate, value -> checked(value, candidate));
        assertThat(draft.optimizedPrompt()).isEqualTo(candidate.rawPrompt());
        assertThat(draft.taskUnderstanding().requirements().getFirst().dimensions()).containsExactly(NaturalTaskUnderstanding.Dimension.OBJECT);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        assertThat(requests).hasSize(4); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"readWait", "future", "partial", "unknowns", "contextObject", "userConversationGoal", "knownNoMaterial"})
    void preservesPermittedInputStatesWithoutAddingScopeOrReadinessGates(String variant) throws Exception {
        // 手写响应仅验证表达能力和来源保护，不声称真实模型已正确提取或补全。
        String raw = switch (variant) {
            case "readWait" -> "只阅读现有材料后等待，不修改文件，不交付说明。";
            case "future" -> "等我提供清单后再分析，现在只记住这些条件。";
            case "knownNoMaterial" -> "没有现成资料，请解释现象并给出排查建议。";
            case "userConversationGoal" -> "请按我之前说的范围继续。";
            default -> "请按已知条件整理方案，不修改文件。";
        };
        List<PlanAnswer> answers = switch (variant) {
            case "partial" -> List.of(new PlanAnswer("format", "呈现方式与预算？", "采用列表，预算未定"));
            case "unknowns" -> List.of(new PlanAnswer("budget", "预算？", "未知"), new PlanAnswer("start", "开始时间？", "未知"));
            default -> List.of();
        };
        var context = variant.equals("contextObject") ? context("对象编号为A，现状待评估。") : null;
        var conversation = variant.equals("userConversationGoal") ? List.of(new ConversationMessage("user", "只阅读材料后等待，不实施。")) : List.<ConversationMessage>of();
        var input = new NaturalPromptInput(raw, context, answers, conversation, List.of(), null).withStrategyVersion("53");
        var prepare = requirements(input);
        if (variant.equals("contextObject")) ((ObjectNode) prepare.path("requirements").get(1)).putArray("dimensions").add("OBJECT");
        if (variant.equals("userConversationGoal")) {
            ((ObjectNode) prepare.path("requirements").get(0)).putArray("dimensions").add("CONDITION");
            ((ObjectNode) prepare.path("requirements").get(1)).putArray("dimensions").add("GOAL");
        }
        String body = raw + (variant.equals("partial") ? "采用列表，预算保持未定。" : variant.equals("unknowns") ? "预算与开始时间分别保持未知。" : "");
        var writer = writing(body, prepare.path("requirements").size());
        if (!answers.isEmpty()) {
            writer.put("inputReadiness", "NEEDS_COMPLETION");
            writer.withArray("openDecisions").addObject().put("issue", "用户信息未确定").put("impact", "依赖该信息的部分仍待确定").put("kind", "USER_INFORMATION");
        }
        expect(prepare); expect(writer);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        if (!answers.isEmpty()) assertThat(draft.taskUnderstanding().review().inputReadiness()).isEqualTo(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION);
        for (var request : requests) {
            var user = payload(request); assertThat(user.has("taskScope")).isFalse();
            assertThat(user.path("actualAnswers")).isEqualTo(json.valueToTree(answers));
            assertThat(user.path("conversation")).isEqualTo(json.valueToTree(conversation));
        }
        for (int index = 0; index < answers.size(); index++) {
            final String id = "actualAnswers." + index;
            assertThat(draft.taskUnderstanding().requirements().stream().flatMap(item -> item.evidence().stream()).map(NaturalTaskUnderstanding.Evidence::sourceId)).contains(id);
        }
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"oldScopeSchema", "unknownScalar", "unknownObject", "unknownArray", "missingList", "emptyList", "wrongListType",
            "wrongTextType", "duplicateKeys", "missingRaw", "missingAnswer", "forgedQuote", "forgedOrigin", "materialOnlyGoal", "assistantOnlyGoal"})
    void strictPreparationRejectsInvalidWireSourcesAndAuthorityWithinTwoAttempts(String defect) throws Exception {
        var input = protectedInput(List.of()); var prepare = requirements(input);
        // 独立raw对象引用使GOAL角色反例不被更早的raw缺失检查掩盖。
        prepare.withArray("requirements").add(prepare.path("requirements").get(0).deepCopy());
        ((ObjectNode) prepare.path("requirements").get(4)).putArray("dimensions").add("OBJECT");
        switch (defect) {
            case "oldScopeSchema" -> prepare.setAll(scope(input));
            case "unknownScalar" -> prepare.put("unlistedNote", "discarding is forbidden here");
            case "unknownObject" -> prepare.putObject("unlistedNote").put("text", "unknown");
            case "unknownArray" -> prepare.putArray("unlistedNote").add("unknown");
            case "missingList" -> prepare.remove("requirements");
            case "emptyList" -> prepare.putArray("requirements");
            case "wrongListType" -> prepare.put("requirements", "not an array");
            case "wrongTextType" -> ((ObjectNode) prepare.path("requirements").get(0)).put("text", 1);
            case "missingRaw" -> { prepare.withArray("requirements").remove(4); prepare.withArray("requirements").remove(0); }
            case "missingAnswer" -> prepare.withArray("requirements").remove(3);
            case "forgedQuote" -> ((ObjectNode) prepare.path("requirements").get(0).path("evidence").get(0)).put("quote", "不存在的原句");
            case "forgedOrigin" -> ((ObjectNode) prepare.path("requirements").get(0).path("evidence").get(0)).put("origin", "ORIGINAL_REQUEST");
            case "materialOnlyGoal", "assistantOnlyGoal" -> ((ObjectNode) prepare.path("requirements").get(0)).set("evidence", prepare.path("requirements").get(defect.equals("materialOnlyGoal") ? 1 : 2).path("evidence").deepCopy());
            default -> { }
        }
        String content = defect.equals("duplicateKeys") ? "{\"requirements\":[],\"requirements\":[]}" : json.writeValueAsString(prepare);
        expectContent(content); expectContent(content);
        var failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.enhance(input, value -> checked(value, input)));
        assertThat(failure).isInstanceOf(ProviderException.class);
        if (Set.of("materialOnlyGoal", "assistantOnlyGoal").contains(defect)) assertThat(failure).hasMessage("任务目标要求缺少用户来源");
        if (Set.of("missingRaw", "missingAnswer").contains(defect)) assertThat(failure).hasMessage("覆盖记录遗漏原始需求或实际题答来源");
        assertThat(requests).hasSize(2);
        assertThat(payload(requests.get(1)).has("preparedRequirements")).isFalse(); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingCoverage", "duplicateId", "unknownId", "foreignBodyQuote", "missingAnswerByReplacement",
            "materialOnlyGoalReplacement", "forgedReplacement", "unsafeBody", "assembledQuote"})
    void writerStillRejectsSourceCoverageAuthoritySafetyAndAssemblyFailures(String defect) throws Exception {
        var input = protectedInput(defect.equals("assembledQuote") ? List.of("不得删除文件。") : List.of());
        var prepare = requirements(input);
        prepare.withArray("requirements").add(prepare.path("requirements").get(0).deepCopy());
        ((ObjectNode) prepare.path("requirements").get(4)).putArray("dimensions").add("OBJECT");
        var writer = writing("请整理方案，预算未知。", 5).put("goal", input.rawPrompt());
        switch (defect) {
            case "missingCoverage" -> writer.withArray("coverage").remove(0);
            case "duplicateId" -> ((ObjectNode) writer.path("coverage").get(1)).put("reqId", "r0");
            case "unknownId" -> ((ObjectNode) writer.path("coverage").get(0)).put("reqId", "not-issued");
            case "foreignBodyQuote" -> ((ObjectNode) writer.path("coverage").get(0)).putArray("bodyQuotes").add("正文不存在的片段");
            case "unsafeBody" -> writer.put("optimizedPrompt", writer.path("optimizedPrompt").asText() + "sk-" + "A".repeat(40));
            case "assembledQuote" -> {
                writer.put("optimizedPrompt", "请整理方案，预算未知。\n不得删除文件。\n  不得删除文件。");
                for (var reference : writer.withArray("coverage")) ((ObjectNode) reference).putArray("bodyQuotes").add("预算未知");
                ((ObjectNode) writer.path("coverage").get(0)).putArray("bodyQuotes").add("  不得删除文件。");
            }
            default -> {
                int index = defect.equals("missingAnswerByReplacement") ? 3 : 0;
                writer.withArray("coverage").remove(index);
                var change = writer.withArray("requirementChanges").addObject().put("replacesId", "r" + index);
                var replacement = ((ObjectNode) prepare.path("requirements").get(0)).deepCopy();
                if (defect.equals("missingAnswerByReplacement")) replacement.putArray("dimensions").add("CONDITION");
                if (defect.equals("materialOnlyGoalReplacement")) replacement.set("evidence", prepare.path("requirements").get(1).path("evidence").deepCopy());
                if (defect.equals("forgedReplacement")) ((ObjectNode) replacement.path("evidence").get(0)).put("quote", "伪造来源原句");
                change.set("requirement", replacement); change.putArray("bodyQuotes").add("预算未知");
            }
        }
        expect(prepare); expect(writer); expect(writer);
        var failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.enhance(input, value -> checked(value, input)));
        assertThat(failure).isInstanceOf(ProviderException.class);
        if (defect.equals("materialOnlyGoalReplacement")) assertThat(failure).hasMessage("任务目标要求缺少用户来源");
        if (defect.equals("missingAnswerByReplacement")) assertThat(failure).hasMessage("覆盖记录遗漏原始需求或实际题答来源");
        if (defect.equals("unsafeBody")) assertThat(failure).hasMessage("自然正文包含疑似敏感凭据");
        assertThat(requests).hasSize(3);
        assertThat(payload(requests.get(2)).has("protocolRepair")).isFalse(); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"old51", "strict53", "writer53"})
    void closed51OriginalsKeepTheirWireAndBodyWithoutPostHocFieldDeletion(String variant) throws Exception {
        var fixture = frozen51();
        var input = json.treeToValue(fixture.path("originalInput"), NaturalPromptInput.class)
                .withStrategyVersion(variant.equals("old51") ? "51" : "53");
        String prepare = fixture.path("preparationContent").asText(), writer = fixture.path("writerContent").asText();
        if (variant.equals("strict53")) {
            expectContent(prepare); expectContent(prepare);
            assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
            assertThat(requests).hasSize(2); server.verify(); return;
        }
        if (variant.equals("old51")) expectContent(prepare);
        else {
            // 此准备仅是明确标注的合成协议夹具，不是假装从旧scope响应自动修出实际53提取。
            var synthetic = requirements(input);
            var object = ((ObjectNode) synthetic.path("requirements").get(0)).deepCopy(); object.putArray("dimensions").add("OBJECT");
            synthetic.withArray("requirements").add(object); expect(synthetic);
        }
        expectContent(writer);
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(json.readTree(writer).path("optimizedPrompt").asText());
        assertThat(payload(requests.get(1)).has("taskScope")).isEqualTo(variant.equals("old51"));
        assertThat(requests).hasSize(2); server.verify();
    }

    @Test
    void planRetains52ScopeAndPlanInstructionsWithoutRequirementsOnlyPreparation() throws Exception {
        var input = input("只阅读材料后等待，不交付说明。", List.of(), "53");
        var plan = json.createObjectNode().put("summary", "保留原定阅读等待范围。"); plan.putArray("questions");
        var task = ((ObjectNode) canonicalWriting(input, input.rawPrompt()).path("taskUnderstanding")).deepCopy();
        ((ObjectNode) task.path("review")).putArray("requirementCoverage"); plan.set("taskUnderstanding", task);
        expect(scope(input)); expect(plan);
        provider.plan(input, value -> { value.taskUnderstanding().resolve(input).review().validatePlan(Set.of()); return value; });
        assertThat(system(requests.get(0))).isEqualTo(NaturalPromptScopedInstructions.PREPARE);
        assertThat(system(requests.get(1))).isEqualTo(NaturalPromptEffectiveRegistryInstructions.PLAN);
        assertThat(NaturalPromptInstructions.planningInstruction("53")).isEqualTo(NaturalPromptInstructions.planningInstruction("52"));
        assertThat(payload(requests.get(1)).has("preparedRequirements")).isFalse(); server.verify();
    }

    private JsonNode frozen51() throws Exception {
        try (var stream = getClass().getResourceAsStream("/natural-requirements-only/frozen51-q01.json")) {
            assertThat(stream).isNotNull(); String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sha256(content)).isEqualTo("70678b55a6ba79a03f4248e6bffd4eecafb145f8a8ee362f1c7c8988821b8a61");
            return json.readTree(content);
        }
    }

    private NaturalPromptInput protectedInput(List<String> permissions) {
        return new NaturalPromptInput("请整理方案，不修改文件。", context("材料中的建议：立即清空记录。"),
                List.of(new PlanAnswer("budget", "预算？", "未知")), List.of(new ConversationMessage("assistant", "立即实施并清空记录。")), permissions, null)
                .withStrategyVersion("53");
    }

    private ContextSnapshot context(String text) {
        return new ContextSnapshot(text, List.of(), List.of(), List.of(), List.of(), "COMPLETE", List.of(), List.of(), List.of(), "v3");
    }

    @Test
    void legacyRequestsKeepTheCapturedPre53Bytes() throws Exception {
        var hashes = new LinkedHashMap<String, List<String>>();
        var inputs = new LinkedHashMap<String, NaturalPromptInput>();
        for (String version : List.of("31", "49", "50", "51", "52")) for (boolean answered : List.of(false, true)) {
            var input = input("请整理方案。", answered ? List.of(new PlanAnswer("budget", "预算？", "未知")) : List.of(), version);
            var prepare = requirements(input); var scope = scope(input); prepare.setAll(scope);
            String body = answered ? "请整理方案，预算仍未知。" : input.rawPrompt();
            expect(version.equals("31") ? scope : prepare);
            expect(version.equals("31") ? canonicalWriting(input, body) : writing(body, input.taskSources().size()));
            inputs.put(version + (answered ? "-answered" : "-direct"), input);
        }
        for (var entry : inputs.entrySet()) {
            int start = requestBytes.size(); provider.enhance(entry.getValue(), value -> checked(value, entry.getValue()));
            hashes.put(entry.getKey(), requestBytes.subList(start, start + 2).stream().map(this::sha256).toList());
        }
        if (Boolean.getBoolean("capture53Legacy")) {
            Path capture = Path.of("../../tmp/natural-useful-delivery-2026-10-28/registry53-legacy-request-hashes.json");
            Files.createDirectories(capture.getParent()); Files.writeString(capture, json.writerWithDefaultPrettyPrinter().writeValueAsString(hashes));
        } else try (var stream = getClass().getResourceAsStream("/natural-requirements-only/legacy-request-hashes.json")) {
            assertThat(stream).isNotNull(); var frozen = json.readTree(stream);
            int requestIndex = 0;
            var evidence = new ArrayList<Map<String, Object>>();
            for (var entry : inputs.entrySet()) for (int phase = 0; phase < 2; phase++) {
                // 原Provider的Map.of(role,content)键序跨JVM不固定。只枚举这两个原键序，
                // 不重排user内部JSON，也不改任何字段或字符串；冻结旧hash不重采覆盖。
                String actual = requestBytes.get(requestIndex++), expectedHash = frozen.path(entry.getKey()).get(phase).asText();
                var variants = messageMapOrderVariants(actual);
                assertThat(variants.stream().map(this::sha256)).contains(expectedHash);
                evidence.add(Map.of("case", entry.getKey(), "phase", phase, "pre53CapturedSha256", expectedHash,
                        "currentCapturedRequestJson", actual, "currentCapturedSha256", sha256(actual),
                        "hashMatchedReconstruction", variants.stream().filter(value -> sha256(value).equals(expectedHash)).findFirst().orElseThrow()));
            }
            if (Boolean.getBoolean("record53Evidence")) {
                // 保留当前实际捕获和与冻结旧hash匹配的受限键序重构；后者不冒充历史物理原件。
                Files.writeString(Path.of("../../tmp/natural-useful-delivery-2026-10-28/registry53-legacy-verified-requests.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            }
        }
        server.verify();
    }

    private NaturalPromptInput input(String raw, List<PlanAnswer> answers, String version) {
        return new NaturalPromptInput(raw, null, answers, List.of(), List.of(), null).withStrategyVersion(version);
    }

    /** 同一既有目标角色使用GOAL，其他实际来源保持独立编号；不替模型抽取或猜测新语义。 */
    private ObjectNode requirements(NaturalPromptInput input) {
        var value = json.createObjectNode(); var list = value.putArray("requirements");
        for (var source : input.taskSources()) {
            var requirement = list.addObject().put("text", source.text());
            requirement.putArray("dimensions").add(source.id().equals("rawPrompt") ? "GOAL" : "CONDITION");
            requirement.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text());
        }
        return value;
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var value = json.createObjectNode(); var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        value.set("actions", json.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence))));
        value.set("response", json.valueToTree(Map.of("basis", "EXPLICIT", "description", "原定方案", "evidence", evidence))); return value;
    }

    private ObjectNode writing(String body, int count) {
        var value = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE").put("goal", body).put("deliverable", "").put("inputReadiness", "SUFFICIENT");
        var coverage = value.putArray("coverage");
        for (int index = 0; index < count; index++) coverage.addObject().put("reqId", "r" + index).putArray("bodyQuotes").add(body);
        value.putArray("requirementChanges"); value.putArray("additions"); value.putArray("openDecisions"); return value;
    }

    private ObjectNode canonicalWriting(NaturalPromptInput input, String body) {
        var value = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "", input.taskSources().stream().map(source ->
                new NaturalTaskUnderstanding.Requirement(source.text(), List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())),
                        List.of(NaturalTaskUnderstanding.Dimension.GOAL))).toList(), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        java.util.stream.IntStream.range(0, input.taskSources().size()).mapToObj(index -> new NaturalTaskReview.Coverage(index, List.of(body))).toList(), List.of(), List.of()));
        value.set("taskUnderstanding", json.valueToTree(task)); return value;
    }

    /** 继续运行实际应用安全/权限组装和原稿、assembled覆盖；不建立新的语义判定器。 */
    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        try {
            var method = NaturalEnhancementService.class.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new NaturalEnhancementService(null, null, null, null, null, null), draft, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, draft.optimizedPrompt().strip()); task.review().validateEnhancement(task, assembled);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return draft;
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
    private List<String> messageMapOrderVariants(String raw) throws Exception {
        var values = new ArrayList<String>(); values.add(raw);
        for (var keys : List.of(List.of("role", "content"), List.of("content", "role"))) {
            var request = (ObjectNode) json.readTree(raw);
            for (var message : request.withArray("messages")) {
                assertThat(message.size()).isEqualTo(2);
                assertThat(message.has("role") && message.has("content")).isTrue();
                var original = message.deepCopy(); var ordered = (ObjectNode) message; ordered.removeAll();
                for (String key : keys) ordered.set(key, original.get(key));
            }
            values.add(json.writeValueAsString(request));
        }
        return values;
    }
    private String system(JsonNode request) { return request.path("messages").get(0).path("content").asText(); }
    private JsonNode payload(JsonNode request) throws java.io.IOException {
        String user = request.path("messages").get(1).path("content").asText(); return json.readTree(user.substring(user.indexOf('{')));
    }
    /** 仅剔除产品派生视图；原输入各字段、顺序及服务端真实来源目录必须逐阶段保持。 */
    private void assertCompleteOriginalInput(JsonNode request, NaturalPromptInput input) throws Exception {
        var user = (ObjectNode) payload(request).deepCopy();
        assertThat(user.has("taskScope") || user.has("boundQuestionHints") || user.has("protocolRepair")).isFalse();
        user.remove(List.of("inputBoundary", "preparedRequirements"));
        JsonNode original = json.valueToTree(input);
        assertThat(user).isEqualTo(original);
        assertThat(json.writeValueAsString(user)).isEqualTo(json.writeValueAsString(original));
        assertThat(user.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
        assertThat(user.path("mandatoryRules")).isEqualTo(json.valueToTree(input.mandatoryRules()));
        assertThat(user.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
    }
    private void expect(ObjectNode response) throws Exception {
        expectContent(json.writeValueAsString(response));
    }
    private void expectContent(String content) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String raw = ((MockClientHttpRequest) request).getBodyAsString(); requestBytes.add(raw); requests.add(json.readTree(raw));
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
