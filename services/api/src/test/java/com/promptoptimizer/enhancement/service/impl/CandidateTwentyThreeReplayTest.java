package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 回放315的动作跨对象误判；合法不可见条件与真实条件披露分别校验。 */
class CandidateTwentyThreeReplayTest {
    private static final String RAW = "为研究准备论文方法，两名独立评审盲评。";

    @Test
    void capturedLegalOptionPassesWhileActualDisclosureRemainsRejected() throws Exception {
        var json = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/enhancement/candidate23-plan315.json")) {
            var fixture = json.readTree(input);
            String original = fixture.toString();
            var question = fixture.path("response").path("questions").findValues("id").isEmpty()
                    ? null : fixture.path("response").path("questions").findParents("options").stream()
                        .filter(value -> value.path("id").asText().equals("q5")).findFirst().orElseThrow();
            assertThat(question).isNotNull();
            var policy = policy(fixture.path("rawPrompt").asText());
            for (var option : question.path("options")) {
                for (String field : List.of("description", "answer")) {
                    String text = option.path(field).asText();
                    if (option.path("id").asText().equals("know_both")) {
                        assertThatThrownBy(() -> policy.validateCandidate(text, "questions.options." + field))
                                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                                        e -> assertThat(e.getReason()).isEqualTo(
                                                ProviderResponseValidationException.Reason.RULE_CONFLICT));
                    } else {
                        assertThatCode(() -> policy.validateCandidate(text, "questions.options." + field))
                                .doesNotThrowAnyException();
                    }
                }
            }
            assertThat(fixture.toString()).isEqualTo(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "评审知道任务类别和任务内容，但不知道输出来自哪一实验条件。",
        "评审知道任务类别但不知道实验条件。",
        "评审知晓评分维度，却不能知晓实验分组。",
        "评审看到匿名编号，而不会看到分组信息。",
        "禁止评审看到实验条件。"
    })
    void knowingAnIndependentObjectDoesNotDiscloseExperimentalCondition(String text) {
        assertThatCode(() -> policy(RAW).validateCandidate(text, "questions.options.answer"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "评审知道任务类别和实验条件，但独立评分。",
        "评审不知道任务类别，但知道实验条件。",
        "禁止评审看到任务类别，但评审知道实验条件。",
        "评审不会看到匿名编号，而会看到分组信息。",
        "评审知道评分维度；评审知晓实验分组。",
        "评审不知道任务类别但知道实验条件。"
    })
    void aNewPositiveDisclosureCannotBorrowAnotherActionsNegation(String text) {
        assertThatThrownBy(() -> policy(RAW).validateCandidate(text, "questions.options.answer"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT));
    }

    @Test
    void unknownBlindingDoesNotBorrowAnotherTasksRestriction() {
        assertThatCode(() -> policy("拟定研究，评审方式尚未确定。")
                .validateCandidate("评审知道实验条件。", "questions.options.answer")).doesNotThrowAnyException();
    }

    /** 只读取本次原始规则，不借题目或模型推荐确认盲法。 */
    private PlanningDecisionPolicy policy(String raw) {
        return PlanningDecisionPolicy.from(new PlanningProviderRequest(raw, "", List.of()));
    }
}
