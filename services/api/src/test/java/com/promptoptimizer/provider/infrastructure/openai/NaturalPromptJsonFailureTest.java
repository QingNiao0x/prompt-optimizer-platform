package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 回放真实失败及正常相邻对象，防止诊断吞掉重复键或泄露模型自定义字段。 */
class NaturalPromptJsonFailureTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void savedDuplicateResponsesIdentifyTheActualTaskField() throws Exception {
        for (String id : new String[] {"0003", "0004", "0034"}) {
            var trace = json.readTree(Path.of("../../docs/testing/evidence/natural-internal-design-adoption-2026-11-07/traces/" + id + ".json").toFile());
            String body = trace.path("response").path("choices").get(0).path("message").path("content").asText();
            var failure = NaturalPromptJsonFailure.diagnose(json, body, new IllegalStateException());
            assertThat(failure.getReason()).isEqualTo(Reason.NATURAL_DUPLICATE_FIELD);
            assertThat(failure.getField()).isEqualTo("task.requirements");
        }
    }

    @Test
    void siblingObjectsCanUseTheSameFieldAndUnknownNamesStayPrivate() {
        var normal = NaturalPromptJsonFailure.diagnose(json,
                "{\"task\":{\"requirements\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}", null);
        assertThat(normal.getReason()).isEqualTo(Reason.NATURAL_JSON_INVALID);
        var duplicate = NaturalPromptJsonFailure.diagnose(json,
                "{\"task\":{\"requirements\":[{\"text\":\"a\",\"text\":\"b\"}]}}", null);
        assertThat(duplicate.getField()).isEqualTo("task.requirements[0].text");
        var privateName = NaturalPromptJsonFailure.diagnose(json, "{\"private-value\":1,\"private-value\":2}", null);
        assertThat(privateName.getField()).isEqualTo("response");
        assertThat(privateName.getMessage()).doesNotContain("private-value");
        assertThat(com.promptoptimizer.common.logging.ModelCallObservation.safeValidationField("task.requirements[0].text"))
                .isEqualTo("task.requirements[0].text");
        assertThat(com.promptoptimizer.common.logging.ModelCallObservation.safeValidationField("task.private-value"))
                .isNull();
    }

    @Test
    void fieldTypeErrorsHaveASafePathAndRetainTheCause() {
        try {
            json.readValue("{\"task\":{\"requirements\":17}}", TaskRoot.class);
            fail("错误类型应被拒绝");
        } catch (com.fasterxml.jackson.databind.JsonMappingException cause) {
            var failure = NaturalPromptJsonFailure.mapping(cause);
            assertThat(failure.getField()).isEqualTo("task.requirements");
            assertThat(failure.getReason()).isEqualTo(Reason.NATURAL_FIELD_INVALID);
            assertThat(failure.getCause()).isSameAs(cause);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    record TaskRoot(Task task) { }
    record Task(java.util.List<String> requirements) { }
}
