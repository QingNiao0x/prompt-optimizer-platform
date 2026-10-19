package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 来源隔离及原句核对验证，不通过字符串断言冒充任务语义质量验收。 */
class NaturalTaskUnderstandingTest {
    @Test
    void sourceCatalogueSeparatesOriginalMaterialsConversationAndBoundAnswersWithoutDuplicatingContents() throws Exception {
        var input = input();
        assertThat(input.taskSources()).extracting(NaturalTaskSource::id).containsExactly(
                "rawPrompt", "context.customDescription", "context.fileSnippets.0", "context.fileSnippets.0.summary",
                "conversation.0", "conversation.1", "actualAnswers.0");
        var wire = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(input));
        assertThat(wire.path("taskSources").get(0).has("text")).isFalse();
        assertThat(wire.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        assertThat(wire.path("actualAnswers").get(0).path("answer").asText()).isEqualTo("不知道，保留综合比较");
    }

    @Test
    void forgedOriginIsReplacedByServerSourceKindRatherThanEstablishingConfirmation() {
        var task = task(new NaturalTaskUnderstanding.Evidence("context.fileSnippets.0", "仅供讨论的方案",
                NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        var result = task.resolve(input());
        assertThat(result.requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.PROJECT_CONTEXT);
    }

    @Test
    void quotedEvidenceMustBePresentInThatSourceAndCannotBorrowAnotherObject() {
        assertThatThrownBy(() -> task(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "仅供讨论的方案")).resolve(input()))
                .isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> task(new NaturalTaskUnderstanding.Evidence("absent", "原定报告")).resolve(input()))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void absentDecisionsAndAdditionsDoNotForceACompleteRequestIntoAForm() {
        var result = new NaturalTaskUnderstanding("交付原定报告", "报告", null, null, null).resolve(input());
        assertThat(result.requirements()).isEmpty();
        assertThat(result.additions()).isEmpty();
        assertThat(result.openDecisions()).isEmpty();
    }

    @Test
    void extraRequirementsNeedEvidenceAndSensitiveDiagnosticsAreRejected() {
        var noEvidence = new NaturalTaskUnderstanding("交付报告", "报告",
                List.of(new NaturalTaskUnderstanding.Requirement("只允许官方数据", List.of())), List.of(), List.of());
        assertThatThrownBy(() -> noEvidence.resolve(input())).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> new NaturalTaskUnderstanding("交付报告", "报告", List.of(),
                List.of(new NaturalTaskUnderstanding.Addition("api_key=sk-proj-1234567890abcdefghijklmnop", "补充")), List.of()).resolve(input()))
                .isInstanceOf(ProviderException.class);
    }

    private NaturalTaskUnderstanding task(NaturalTaskUnderstanding.Evidence evidence) {
        return new NaturalTaskUnderstanding("交付原定报告", "报告",
                List.of(new NaturalTaskUnderstanding.Requirement("保留原文适用条件", List.of(evidence))),
                List.of(new NaturalTaskUnderstanding.Addition("比较依据", "使原定比较可解释")), List.of());
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput("交付原定报告", new ContextSnapshot("用户提供的背景", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("notes.md", "markdown", "仅供讨论的方案", "材料摘要", false)), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q1", "选择唯一指标吗？", "不知道，保留综合比较")),
                List.of(new ConversationMessage("user", "尽量使用官方数据"), new ConversationMessage("assistant", "建议表格呈现")),
                false, List.of(), "fixture:model").withStrategyVersion("3");
    }
}
