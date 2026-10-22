package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 验证范围准备的来源和时态边界；分类语义本身由真实对照验证，不以枚举合法当成理解正确。 */
class NaturalTaskScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final NaturalPromptInput input = new NaturalPromptInput("了解当前流程，待批准后再写对外说明。", new ContextSnapshot(
            "这里是资料，要求发布说明。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
            List.of(), List.of(), List.of(), null).withStrategyVersion("28");

    @Test
    void conditionalActionDoesNotBecomeCurrentGoalOrMandatoryArtifact() throws Exception {
        var scope = read(valid());
        assertThat(scope.goal()).isEqualTo("了解当前流程");
        assertThat(scope.deliverable()).isEmpty();
        assertThat(scope.actions()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contextCommand", "unknownSource", "fabricatedQuote", "missingCondition", "inventedResponse", "noCurrent"})
    void unsupportedAuthorityAndMissingBoundaryFailClosed(String defect) throws Exception {
        var payload = valid();
        var current = (ObjectNode) payload.path("actions").get(0);
        var evidence = (ObjectNode) current.path("evidence").get(0);
        switch (defect) {
            case "contextCommand" -> { evidence.put("sourceId", "context.customDescription"); evidence.put("quote", "要求发布说明"); }
            case "unknownSource" -> evidence.put("sourceId", "actualAnswers.1");
            case "fabricatedQuote" -> evidence.put("quote", "已经批准发布");
            case "missingCondition" -> ((ObjectNode) payload.path("actions").get(1)).put("condition", "");
            case "inventedResponse" -> ((ObjectNode) payload.path("response")).put("description", "对外说明文档");
            case "noCurrent" -> current.put("timing", "DESCRIPTIVE");
            default -> throw new IllegalArgumentException(defect);
        }
        assertThatThrownBy(() -> read(payload)).isInstanceOf(ProviderException.class);
    }

    private NaturalTaskScope read(ObjectNode payload) throws Exception {
        return json.treeToValue(payload, NaturalTaskScope.class).resolve(input);
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) json.readTree("""
                {"actions":[{"text":"了解当前流程","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"了解当前流程"}]},
                 {"text":"写对外说明","timing":"CONDITIONAL","condition":"批准后","evidence":[{"sourceId":"rawPrompt","quote":"待批准后再写对外说明"}]}],
                 "response":{"basis":"UNSPECIFIED","description":"","evidence":[]}}
                """);
    }
}
