package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证材料已区分状态时，地区无法核验不能被候选答案冒充为整个查询失败。
 * 未知处理策略、真实网络失败及无此证据的任务仍保留原来的决定空间。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class UnverifiableRegionCandidateTest {
    @Test
    void shouldRetainTheExplicitStateSeparationRuleAsASourcedFact() {
        String rule = "方案应把无法核验的记录与确无候选、明确地区不符分别说明。";
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("materials/software/current-brief.md", "markdown", rule, "地区补值资料", false),
                new FileSnippet("materials/software/tests/example.txt", "text", rule, "测试样例", false)), List.of(), List.of(), "v1");
        var facts = new PlanningFactCardExtractor().extract(context, "制定地区表单补值方案，地区无法核验时的策略尚未决定");
        assertThat(facts.cards()).anySatisfy(card -> {
            assertThat(card.sourcePath()).isEqualTo("materials/software/current-brief.md");
            assertThat(card.evidence()).contains("无法核验", "确无候选", "地区不符", "分别说明");
        }).noneMatch(card -> card.sourcePath().contains("/tests/"));
    }

    @Test
    void shouldRejectOnlyTheProvenConflationAndKeepOtherChoices() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(
                "[USER_MATERIAL] materials/software/current-brief.md：方案应把无法核验的记录与确无候选、明确地区不符分别说明。"),
                "COMPLETE", 1, List.of());
        var policy = PlanningDecisionPolicy.from(new PlanningProviderRequest(
                "制定表单补值方案，地区缺失的处理策略尚未决定。", "", List.of(), digest));
        assertThatThrownBy(() -> policy.validateCandidate(
                "只要候选集中存在地区信息缺失或无法核验的候选，就按查询失败处理，保留原值并显示可理解的失败提示。",
                "questions.options.answer")).isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> policy.validateCandidate(
                "地区信息缺失或无法核验的候选一律排除，不进入候选列表，也不向用户提示存在此类记录。", "candidate"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> policy.validateCandidate("无法核验地区时，展示但禁止补值，并单独解释原因。", "candidate"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateCandidate("无法核验地区不得按查询失败处理。", "candidate"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateCandidate("查询接口真实失败时，保留原值并显示失败提示。", "candidate"))
                .doesNotThrowAnyException();
        var unknown = PlanningDecisionPolicy.from(new PlanningProviderRequest("设计候选核验策略", "", List.of()));
        assertThatCode(() -> unknown.validateCandidate("无法核验地区时按查询失败处理。", "candidate"))
                .doesNotThrowAnyException();
    }
}
