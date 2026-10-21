package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 完整示范必须满足与真实增强相同的来源、全部题答和正文锚点协议，不能降低保护以便示范通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptDemonstrationsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shippedExamplesIncludeACompleteBoundAnswerAndSufficientInputWithoutProtocolRelaxation() {
        assertThat(NaturalPromptDemonstrations.instruction()).contains("internal_rewrite_demonstrations", "actualAnswers.1", "SUFFICIENT");
        assertThat(NaturalPromptDemonstrations.instruction()).isSameAs(NaturalPromptDemonstrations.instruction());
    }

    @Test
    void fabricatedSourceAndWrongBodyAnchorCannotBecomeTeachingMaterial() throws Exception {
        for (String part : new String[]{"source", "anchor", "answeredPlan"}) {
            ArrayNode examples = examples();
            var task = (ObjectNode) examples.get(1).path("output").path("taskUnderstanding");
            if (part.equals("source")) {
                ((ObjectNode) task.path("requirements").get(1).path("evidence").get(0)).put("sourceId", "actualAnswers.99");
            } else if (part.equals("anchor")) {
                ((ObjectNode) task.path("review").path("requirementCoverage").get(0))
                        .putArray("quotes").add("另一份正文里的通知要求");
            } else {
                ((ObjectNode) task.path("review")).putArray("planImpacts").addObject()
                        .put("questionId", "time").put("whyAsk", "已回答的问题不能冒充正在提问")
                        .put("answerDifference", "保留未知不等于新生成Plan");
            }
            assertThatThrownBy(() -> NaturalPromptDemonstrations.render(examples)).isInstanceOf(ProviderException.class);
        }
    }

    @Test
    void otherValidEvidenceCannotHideAnOmittedIndependentAnswerSource() throws Exception {
        var examples = examples();
        ((ArrayNode) examples.get(1).path("output").path("taskUnderstanding").path("requirements")).remove(2);
        assertThatThrownBy(() -> NaturalPromptDemonstrations.render(examples)).isInstanceOf(NaturalTaskReview.MissingSources.class);
    }

    /** 每次读取独立副本供反例修改，避免破坏生产缓存的固定示范。 */
    private ArrayNode examples() throws Exception {
        try (var input = getClass().getResourceAsStream("/prompts/natural/rewrite-demonstrations.json")) {
            return (ArrayNode) json.readTree(input);
        }
    }
}
