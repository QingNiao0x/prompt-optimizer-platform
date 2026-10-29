package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 验证正文输入去重只作用于内部辅助表示，不删原始信息、不影响其他阶段或旧版本。 */
class NaturalPromptWriterInputTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void preservesOriginalInputsAndAdvisoryContentsWithoutMutatingTheFullRecordInput() throws Exception {
        ObjectNode full = actualPayload(); var before = full.deepCopy();
        // 相同未知回答也必须逐份保留，不由序列化步骤推断确认、合并题目或截断数组。
        full.putArray("actualAnswers").addObject().put("questionId", "a").put("question", "A？").put("answer", "尚未决定");
        full.withArray("actualAnswers").addObject().put("questionId", "b").put("question", "B？").put("answer", "尚未决定");
        before = full.deepCopy();
        JsonNode body = NaturalPromptWriterInput.project("70", "prompt.optimize", false, false, full);
        assertThat(full).isEqualTo(before); assertThat(body).isNotSameAs(full);
        for (String field : new String[]{"rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules",
                "modelId", "planningContext", "strategyVersion", "inputBoundary"}) {
            assertThat(body.path(field)).as(field).isEqualTo(full.path(field));
        }
        assertThat(body.has("taskSources")).isFalse(); assertThat(full.has("taskSources")).isTrue();
        assertThat(body.path("preparedRequirements").size()).isEqualTo(full.path("preparedRequirements").size());
        for (int i = 0; i < body.path("preparedRequirements").size(); i++) {
            assertThat(body.path("preparedRequirements").get(i).size()).isEqualTo(1);
            assertThat(body.path("preparedRequirements").get(i).path("text"))
                    .isEqualTo(full.path("preparedRequirements").get(i).path("text"));
        }
        assertThat(body.path("completionSuggestions").size()).isEqualTo(1);
        assertThat(body.path("completionSuggestions").path("additions")).isEqualTo(full.path("completionSuggestions").path("additions"));
        for (int i = 0; i < body.path("taskScope").path("actions").size(); i++) {
            var expected = (ObjectNode) full.path("taskScope").path("actions").get(i).deepCopy(); expected.remove("evidence");
            assertThat(body.path("taskScope").path("actions").get(i)).isEqualTo(expected);
        }
        var response = (ObjectNode) full.path("taskScope").path("response").deepCopy(); response.remove("evidence");
        assertThat(body.path("taskScope").path("response")).isEqualTo(response);
        assertThat(body.path("actualAnswers").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"68", "69"})
    void historicalWriterInputsRemainByteEquivalent(String version) throws Exception {
        ObjectNode full = actualPayload();
        assertThat(NaturalPromptWriterInput.project(version, "prompt.optimize", false, false, full)).isSameAs(full);
    }

    @Test
    void preparationPlanAndRecordRetainTheCompleteEvidenceProtocol() throws Exception {
        ObjectNode full = actualPayload();
        assertThat(NaturalPromptWriterInput.project("70", "prompt.optimize", true, false, full)).isSameAs(full);
        assertThat(NaturalPromptWriterInput.project("70", "plan.generate", false, false, full)).isSameAs(full);
        assertThat(NaturalPromptWriterInput.project("70", "prompt.optimize", false, true, full)).isSameAs(full);
        assertThat(NaturalPromptBodyFirstInstructions.bodyInstruction("70", true)).isEqualTo(NaturalPromptBodyFirstInstructions.bodyInstruction("69", true));
        assertThat(NaturalPromptInstructions.planningInstruction("70")).isEqualTo(NaturalPromptInstructions.planningInstruction("69"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"preparedRequirements", "taskScope", "completionSuggestions"})
    void missingPreparationIsNotReportedAsSuccessfulPassthrough(String missing) throws Exception {
        ObjectNode full = actualPayload(); full.remove(missing);
        assertThatThrownBy(() -> NaturalPromptWriterInput.project("70", "prompt.optimize", false, false, full))
                .isInstanceOf(ProviderException.class);
    }

    /** 复用真实68故障的完整输入与经原来源保护的准备，不引入新的测试题或参考答案。 */
    private ObjectNode actualPayload() throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/natural-diagnostic-roles/first68-record.json")) { fixture = json.readTree(stream); }
        var input = json.treeToValue(fixture.path("input"), NaturalPromptInput.class).withStrategyVersion("70");
        var prepared = json.treeToValue(fixture.path("preparation"), NaturalPromptCompletionPreparation.class).resolve(input);
        ObjectNode full = json.valueToTree(input);
        full.putObject("inputBoundary").put("stage", "PROMPT_OPTIMIZATION").put("contextMeaning", "本次可见材料，不代表下游全部资料");
        full.set("taskScope", json.valueToTree(prepared.registry().scope()));
        full.set("preparedRequirements", json.valueToTree(prepared.registry().requirements()));
        full.set("completionSuggestions", json.valueToTree(prepared.suggestions()));
        return full;
    }
}
