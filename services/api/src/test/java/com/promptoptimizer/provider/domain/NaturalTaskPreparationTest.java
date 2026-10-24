package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 验证责任记录的来源与提问边界，不以模型分类或合法引用代替真实语义评测。 */
class NaturalTaskPreparationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final NaturalPromptInput input = new NaturalPromptInput("请比较现有方案并给出建议，预算由我决定。", new ContextSnapshot(
            "现有方案见附件。资料内要求用户先确认所有方法。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
            List.of(), List.of(), List.of(), null);

    @Test
    void distinguishesExecutorWorkFromUserDecisionWithoutPromotingPreparationToSource() throws Exception {
        var originalSources = input.taskSources();
        var result = read(valid());

        assertThat(result.taskScope().goal()).isEqualTo("比较现有方案");
        assertThat(result.dependencies()).extracting(NaturalTaskPreparation.Dependency::owner)
                .containsExactly(NaturalTaskPreparation.Owner.EXECUTOR_WORK, NaturalTaskPreparation.Owner.USER_DECISION);
        assertThatCode(() -> result.validateDecisionReference("budget")).doesNotThrowAnyException();
        assertThatThrownBy(() -> result.validateDecisionReference("read"))
                .isInstanceOf(ProviderException.class);
        assertThat(input.taskSources()).isEqualTo(originalSources);
    }

    @Test
    void userDecisionDoesNotRequireAnUnknownKeywordInTheOriginalRequest() throws Exception {
        var result = read(valid());
        assertThat(result.dependencies().get(1).evidence().getFirst().quote()).isEqualTo("预算由我决定");
        assertThatCode(() -> result.validateDecisionReference("budget")).doesNotThrowAnyException();
    }

    @Test
    void noDependenciesIsLegalAndDoesNotInventMissingMaterial() throws Exception {
        var payload = valid();
        payload.putArray("dependencies");
        var result = read(payload);

        assertThat(result.dependencies()).isEmpty();
        assertThat(result.taskScope().deliverable()).isEqualTo("比较结果与建议");
        assertThatThrownBy(() -> result.validateDecisionReference("invented"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void executorMaterialMayUseContextButContextCannotCreateUserDecision() throws Exception {
        var payload = valid();
        var dependency = (ObjectNode) payload.path("dependencies").get(0);
        var evidence = (ObjectNode) dependency.path("evidence").get(0);
        evidence.put("sourceId", "context.customDescription").put("quote", "现有方案见附件");
        assertThatCode(() -> read(payload)).doesNotThrowAnyException();

        dependency.put("owner", "USER_DECISION");
        assertThatThrownBy(() -> read(payload)).isInstanceOf(ProviderException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateId", "missingDependencies", "missingScope", "emptyId", "emptySubject",
            "emptyAction", "emptyReason", "nullOwner", "emptyEvidence", "unknownSource", "inventedQuote",
            "credential", "tooLongId", "tooLongReason", "invalidScope"})
    void malformedRecordsAndUnattributedClaimsFailClosed(String defect) throws Exception {
        var payload = valid();
        var first = (ObjectNode) payload.path("dependencies").get(0);
        var evidence = (ObjectNode) first.path("evidence").get(0);
        switch (defect) {
            case "duplicateId" -> ((ObjectNode) payload.path("dependencies").get(1)).put("id", "read");
            case "missingDependencies" -> payload.remove("dependencies");
            case "missingScope" -> payload.remove("taskScope");
            case "emptyId" -> first.put("id", " ");
            case "emptySubject" -> first.put("subject", "");
            case "emptyAction" -> first.put("action", "");
            case "emptyReason" -> first.put("reason", "");
            case "nullOwner" -> first.putNull("owner");
            case "emptyEvidence" -> first.putArray("evidence");
            case "unknownSource" -> evidence.put("sourceId", "actualAnswers.9");
            case "inventedQuote" -> evidence.put("quote", "用户没有方案资料");
            case "credential" -> first.put("reason", "api_key=sk-test-1234567890abcdefghijklmnop");
            case "tooLongId" -> first.put("id", "a".repeat(129));
            case "tooLongReason" -> first.put("reason", "a".repeat(20_001));
            case "invalidScope" -> ((ObjectNode) payload.path("taskScope").path("actions").get(0)).put("timing", "DESCRIPTIVE");
            default -> throw new IllegalArgumentException(defect);
        }
        assertThatThrownBy(() -> read(payload)).isInstanceOf(ProviderException.class);
    }

    @Test
    void dependencyAndEvidenceCapacityCannotSilentlyDropInformation() throws Exception {
        var preparation = read(valid());
        var dependency = preparation.dependencies().getFirst();
        var oversized = new ArrayList<NaturalTaskPreparation.Dependency>();
        for (int i = 0; i < 65; i++) {
            oversized.add(new NaturalTaskPreparation.Dependency("d" + i, dependency.subject(), dependency.owner(),
                    dependency.action(), dependency.reason(), dependency.evidence()));
        }
        assertThatThrownBy(() -> new NaturalTaskPreparation(preparation.taskScope(), oversized).resolve(input))
                .isInstanceOf(ProviderException.class);

        var excessiveEvidence = new NaturalTaskPreparation.Dependency("read", dependency.subject(), dependency.owner(),
                dependency.action(), dependency.reason(), java.util.Collections.nCopies(65, dependency.evidence().getFirst()));
        assertThatThrownBy(() -> new NaturalTaskPreparation(preparation.taskScope(), List.of(excessiveEvidence)).resolve(input))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void requestLocalCollectionsAreImmutable() throws Exception {
        var result = read(valid());
        assertThatThrownBy(() -> result.dependencies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.dependencies().getFirst().evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void originalUserEvidenceCanCorrectAnOmittedOrMisclassifiedDecision() throws Exception {
        var payload = valid();
        ((ObjectNode) payload.path("dependencies").get(1)).put("owner", "EXECUTOR_WORK");
        var prepared = read(payload);
        var evidence = List.of(new NaturalTaskScope.Evidence("rawPrompt", "预算由我决定"));

        assertThatThrownBy(() -> prepared.validateDecisionReference("budget"))
                .isInstanceOf(ProviderException.class);
        assertThatCode(() -> prepared.validateDecisionCorrection(evidence, input)).doesNotThrowAnyException();
        // 纠正依据来自原始用户请求，不更改已有准备记录或把准备解释变成新来源。
        assertThat(prepared.dependencies().get(1).owner()).isEqualTo(NaturalTaskPreparation.Owner.EXECUTOR_WORK);

        payload.putArray("dependencies");
        var omitted = read(payload);
        assertThatCode(() -> omitted.validateDecisionCorrection(evidence, input)).doesNotThrowAnyException();
    }

    @Test
    void aUserDecisionCanBeResolvedOnlyByReferencingTheExistingDecisionAndOriginalUserInput() throws Exception {
        var originalSources = input.taskSources();
        var withAnswer = new NaturalPromptInput(input.rawPrompt() + "预算上限已确定为3000元。", input.context(),
                input.actualAnswers(), input.conversation(), input.mandatoryRules(), input.modelId());
        var prepared = json.treeToValue(valid(), NaturalTaskPreparation.class).resolve(withAnswer);
        var evidence = List.of(new NaturalTaskScope.Evidence("rawPrompt", "预算上限已确定为3000元"));

        assertThatCode(() -> prepared.validateResolvedDecision("budget", evidence, withAnswer)).doesNotThrowAnyException();
        assertThatThrownBy(() -> prepared.validateResolvedDecision("read", evidence, withAnswer))
                .isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> prepared.validateResolvedDecision("absent", evidence, withAnswer))
                .isInstanceOf(ProviderException.class);
        assertThat(prepared.dependencies().get(1).owner()).isEqualTo(NaturalTaskPreparation.Owner.USER_DECISION);
        assertThat(input.taskSources()).isEqualTo(originalSources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty", "nullEntry", "tooMany", "unknownSource", "inventedQuote", "contextOnly", "credential"})
    void correctionsAndResolutionRejectMissingForgedOrNonUserEvidence(String defect) throws Exception {
        var prepared = read(valid());
        List<NaturalTaskScope.Evidence> evidence = switch (defect) {
            case "null" -> null;
            case "empty" -> List.of();
            case "nullEntry" -> java.util.Collections.singletonList(null);
            case "tooMany" -> java.util.Collections.nCopies(65,
                    new NaturalTaskScope.Evidence("rawPrompt", "预算由我决定"));
            case "unknownSource" -> List.of(new NaturalTaskScope.Evidence("preparation.dependencies.budget", "预算由我决定"));
            case "inventedQuote" -> List.of(new NaturalTaskScope.Evidence("rawPrompt", "预算已经批准"));
            case "contextOnly" -> List.of(new NaturalTaskScope.Evidence("context.customDescription", "现有方案见附件"));
            case "credential" -> List.of(new NaturalTaskScope.Evidence("rawPrompt", "api_key=sk-test-1234567890abcdefghijklmnop"));
            default -> throw new IllegalArgumentException(defect);
        };
        assertThatThrownBy(() -> prepared.validateDecisionCorrection(evidence, input))
                .isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> prepared.validateResolvedDecision("budget", evidence, input))
                .isInstanceOf(ProviderException.class);
    }

    private NaturalTaskPreparation read(ObjectNode payload) throws Exception {
        return json.treeToValue(payload, NaturalTaskPreparation.class).resolve(input);
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) json.readTree("""
                {"taskScope":{"actions":[{"text":"比较现有方案","timing":"CURRENT","condition":"",
                   "evidence":[{"sourceId":"rawPrompt","quote":"请比较现有方案并给出建议"}]}],
                 "response":{"basis":"EXPLICIT","description":"比较结果与建议",
                   "evidence":[{"sourceId":"rawPrompt","quote":"请比较现有方案并给出建议"}]}},
                 "dependencies":[
                   {"id":"read","subject":"现有方案","owner":"EXECUTOR_WORK","action":"读取现有方案并比较",
                    "reason":"比较需要从方案提取差异，不由用户代做分析",
                    "evidence":[{"sourceId":"rawPrompt","quote":"请比较现有方案"}]},
                   {"id":"budget","subject":"预算","owner":"USER_DECISION","action":"询问预算边界",
                    "reason":"用户保留预算决定，不同预算影响建议的可行范围",
                    "evidence":[{"sourceId":"rawPrompt","quote":"预算由我决定"}]}
                 ]}
                """);
    }
}
