package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放本轮科研、论文、机关报告与数据分析的真实提醒，保留首轮文本及解释里的新条件。
 * 只检验归并边界，不调用模型，不把回放通过等同于真实 Provider 复验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class FidelityReminderReplayTest {
    @ParameterizedTest
    @ValueSource(strings = {"fidelity-reminder-replay.json", "fidelity-reminder-recheck.json", "fidelity-followup-replay.json"})
    void shouldGroupRealExplanationsAndRetainAllNewDetails(String resource) throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/" + resource)) {
            var cases = new ObjectMapper().readTree(stream);
            for (var sample : cases) {
                List<PlanAnswer> answers = new ArrayList<>();
                sample.get("answers").forEach(answer -> answers.add(new PlanAnswer(answer.get("questionId").asText(),
                        answer.get("question").asText(), answer.get("answer").asText())));
                List<String> findings = new ArrayList<>();
                sample.get("findings").forEach(finding -> findings.add(finding.asText()));
                var merged = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers))
                        .merge(findings, List.of(), List.of());
                assertThat(merged.executionPrerequisites()).as(sample.get("id").asText())
                        .hasSize(sample.get("expectedCount").asInt());
                String copied = String.join("\n", merged.executionPrerequisites());
                assertThat(copied).doesNotContain("confirmedDecisions");
                if (sample.has("preserve")) {
                    sample.get("preserve").forEach(value -> assertThat(copied).contains(value.asText()));
                }
                for (String finding : findings) {
                    // 模型的后续解释包含版本、适用对象和分组等信息，不能因合并题干而丢失。
                    int metadataEnd = finding.indexOf("）：");
                    int endOfHeading = metadataEnd >= 0 ? metadataEnd + 1 : finding.indexOf('。');
                    if (endOfHeading >= 0 && endOfHeading < finding.length() - 1) {
                        assertThat(copied).contains(finding.substring(endOfHeading + 1));
                    }
                }
            }
        }
    }
}
