package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检测阶段只定位实质偏差，修订阶段不能借重建任务记录改变已定位的问题集合。
 * 精确引文与跨度校验只证明协议边界，不代表模型已经正确判断语义。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptFidelityFindingsTest {
    private static final String RAW = "请写一条通知，只输出正文；活动是否举行和发送时间都未定。";
    private static final String ORIGINAL = "  请写一条通知。\r\n活动已经确认；发送时间为周五。\n尾部  ";

    @Test
    void noFindingsPreserveTheOriginalDraftAndItsTaskRecord() {
        var draft = draft(ORIGINAL, task("请写一条通知"));
        var findings = new NaturalPromptFidelityFindings(List.of());
        assertThatCode(() -> findings.validate(input(), draft)).doesNotThrowAnyException();
        assertThat(findings.applyRepair(input(), draft, new NaturalPromptFidelityReview(List.of(), null)))
                .isSameAs(draft);
    }

    @Test
    void absentFindingsAndMalformedEntriesAreNotAnEmptyPass() {
        assertInvalid(new NaturalPromptFidelityFindings(null), draft(ORIGINAL));
        assertInvalid(new NaturalPromptFidelityFindings(Arrays.asList((NaturalPromptFidelityFindings.Finding) null)), draft(ORIGINAL));
        for (var finding : List.of(
                new NaturalPromptFidelityFindings.Finding(null, "rawPrompt", "请写一条通知", "活动已经确认", "会改变活动状态"),
                finding("", "请写一条通知", "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", "", "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", "请写一条通知", "", "会改变活动状态"),
                finding("rawPrompt", "请写一条通知", "活动已经确认", null),
                finding("rawPrompt", "请写一条通知", "活动已经确认", " "))) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(finding)), draft(ORIGINAL));
        }
    }

    @Test
    void findingsRequireAnOriginalSourceAndItsOwnExactQuote() {
        for (String source : List.of("fidelityDraft", "fidelityFindings", "completionSuggestions", "actualAnswers.0")) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(
                    finding(source, "活动是否举行和发送时间都未定", "活动已经确认", "会改变活动状态"))), draft(ORIGINAL));
        }
        assertInvalid(new NaturalPromptFidelityFindings(List.of(
                finding("rawPrompt", "已经确认活动举行", "活动已经确认", "会改变活动状态"))), draft(ORIGINAL));
    }

    @Test
    void findingsNeedUniqueNonOverlappingSpansInTheOriginalBody() {
        for (String quote : List.of("首稿没有这一句", "已确认")) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(
                    finding("rawPrompt", "请写一条通知", quote, "会改变活动状态"))), draft("请写一条通知。已确认，已确认。"));
        }
        var first = finding("rawPrompt", "请写一条通知", "活动已经确认", "会改变活动状态");
        for (var next : List.of(first, finding("rawPrompt", "请写一条通知", "已经确认", "会改变活动状态"))) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(first, next)), draft(ORIGINAL));
        }
    }

    @Test
    void findingsCapacityIsSixteenWithoutSilentlyDroppingFurtherProblems() {
        var findings = new ArrayList<NaturalPromptFidelityFindings.Finding>();
        var body = new StringBuilder("请写一条通知。");
        for (int index = 0; index < 17; index++) {
            String quote = "问题" + index + "。";
            body.append(quote);
            findings.add(finding("rawPrompt", "请写一条通知", quote, "该要求的状态发生变化"));
        }
        var original = draft(body.toString());
        assertThatCode(() -> new NaturalPromptFidelityFindings(findings.subList(0, 16)).validate(input(), original))
                .doesNotThrowAnyException();
        assertInvalid(new NaturalPromptFidelityFindings(findings), original);
    }

    @Test
    void everyFindingFieldRejectsCredentialsIncludingTheImpactExplanation() {
        String secret = "password=fixture-only-credential";
        for (var finding : List.of(
                finding(secret, "请写一条通知", "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", secret, "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", "请写一条通知", secret, "会改变活动状态"),
                finding("rawPrompt", "请写一条通知", "活动已经确认", secret))) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(finding)), draft(ORIGINAL));
        }
    }

    @Test
    void impactExplanationHasATwentyThousandCharacterLimitWithoutTruncation() {
        var valid = new NaturalPromptFidelityFindings(List.of(
                finding("rawPrompt", "请写一条通知", "活动已经确认", "文".repeat(20_000))));
        assertThatCode(() -> valid.validate(input(), draft(ORIGINAL))).doesNotThrowAnyException();
        assertInvalid(new NaturalPromptFidelityFindings(List.of(
                finding("rawPrompt", "请写一条通知", "活动已经确认", "文".repeat(20_001)))), draft(ORIGINAL));
        for (var finding : List.of(
                finding("文".repeat(20_001), "请写一条通知", "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", "文".repeat(20_001), "活动已经确认", "会改变活动状态"),
                finding("rawPrompt", "请写一条通知", "文".repeat(20_001), "会改变活动状态"))) {
            assertInvalid(new NaturalPromptFidelityFindings(List.of(finding)), draft(ORIGINAL));
        }
    }

    @Test
    void aRepairMayChangeReplacementOnlyAndPreservesAllSurroundingBytes() {
        var first = finding("rawPrompt", "活动是否举行和发送时间都未定", "活动已经确认", "会把未决活动写成确定状态");
        var second = finding("rawPrompt", "发送时间都未定", "发送时间为周五", "会编造发送时间");
        var findings = new NaturalPromptFidelityFindings(List.of(first, second));
        var original = draft(ORIGINAL);
        var repaired = findings.applyRepair(input(), original, new NaturalPromptFidelityReview(List.of(
                issue(first, "活动尚未确定"), issue(second, "发送时间尚未确定")), task("请写一条通知")));
        assertThat(repaired.optimizedPrompt()).isEqualTo("  请写一条通知。\r\n活动尚未确定；发送时间尚未确定。\n尾部  ");
        assertThat(repaired.framework()).isSameAs(original.framework());
        assertThat(repaired.provider()).isSameAs(original.provider());
        assertThat(original.optimizedPrompt()).isEqualTo(ORIGINAL);
    }

    @Test
    void aRepairCannotChangeTheDetectedProblemTypeOrItsSourceCoordinates() {
        var detected = finding("rawPrompt", "活动是否举行和发送时间都未定", "活动已经确认", "会把未决活动写成确定状态");
        var findings = new NaturalPromptFidelityFindings(List.of(detected));
        for (var changed : List.of(
                new NaturalPromptFidelityReview.Issue(NaturalPromptFidelityReview.Type.UNSUPPORTED_FACT,
                        detected.sourceId(), detected.sourceQuote(), detected.bodyQuote(), "活动待定"),
                new NaturalPromptFidelityReview.Issue(detected.type(), "review", detected.sourceQuote(), detected.bodyQuote(), "活动待定"),
                new NaturalPromptFidelityReview.Issue(detected.type(), detected.sourceId(), "请写一条通知", detected.bodyQuote(), "活动待定"),
                new NaturalPromptFidelityReview.Issue(detected.type(), detected.sourceId(), detected.sourceQuote(), "已经确认", "尚未确定"))) {
            assertRepairInvalid(findings, new NaturalPromptFidelityReview(List.of(changed), task("请写一条通知")));
        }
    }

    @Test
    void aRepairCannotAddDropOrReorderDetectedProblems() {
        var first = finding("rawPrompt", "活动是否举行和发送时间都未定", "活动已经确认", "会改变活动状态");
        var second = finding("rawPrompt", "发送时间都未定", "发送时间为周五", "会编造发送时间");
        var findings = new NaturalPromptFidelityFindings(List.of(first, second));
        for (var issues : List.of(List.of(issue(first, "活动待定")),
                List.of(issue(second, "时间待定"), issue(first, "活动待定")),
                List.of(issue(first, "活动待定"), issue(second, "时间待定"),
                        new NaturalPromptFidelityReview.Issue(first.type(), "rawPrompt", "请写一条通知", "尾部", "")))) {
            assertRepairInvalid(findings, new NaturalPromptFidelityReview(issues, task("请写一条通知")));
        }
        assertRepairInvalid(findings, new NaturalPromptFidelityReview(List.of(), null));
        assertRepairInvalid(findings, null);
    }

    @Test
    void aNoIssueDetectionDoesNotAuthorizeAnUnrequestedRepair() {
        var findings = new NaturalPromptFidelityFindings(List.of());
        var added = new NaturalPromptFidelityReview(List.of(new NaturalPromptFidelityReview.Issue(
                NaturalPromptFidelityReview.Type.STATE_PROMOTION, "rawPrompt", "请写一条通知", "活动已经确认", "活动待定")),
                task("请写一条通知"));
        assertRepairInvalid(findings, added);
    }

    @Test
    void matchingTheFindingDoesNotBypassFinalBodyCoverageOrSecretProtection() {
        var finding = finding("rawPrompt", "请写一条通知", "活动已经确认", "会改变活动状态");
        var findings = new NaturalPromptFidelityFindings(List.of(finding));
        assertRepairInvalid(findings, new NaturalPromptFidelityReview(List.of(issue(finding, "活动待定")), null));
        assertRepairInvalid(findings, new NaturalPromptFidelityReview(List.of(issue(finding, "活动待定")), task("活动已经确认")));
        assertRepairInvalid(findings, new NaturalPromptFidelityReview(List.of(issue(finding, "password=fixture-only-credential")),
                task("请写一条通知")));
    }

    @Test
    void aRepairCannotLoseAnActualAnswerWhenRebuildingTheTaskRecord() {
        var bound = inputWithAnswer();
        var finding = finding("actualAnswers.0", "时间未定", "发送时间为周五", "会把未知的真实回答改成周五");
        var findings = new NaturalPromptFidelityFindings(List.of(finding));
        var original = draft(ORIGINAL, taskWithAnswer("发送时间为周五"));
        assertThatCode(() -> findings.validate(bound, original)).doesNotThrowAnyException();
        var lostAnswer = new NaturalPromptFidelityReview(List.of(issue(finding, "发送时间待定")), task("请写一条通知"));
        assertThatThrownBy(() -> findings.applyRepair(bound, original, lostAnswer)).isInstanceOf(ProviderException.class);
        var repaired = findings.applyRepair(bound, original, new NaturalPromptFidelityReview(
                List.of(issue(finding, "发送时间待定")), taskWithAnswer("发送时间待定")));
        assertThat(repaired.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
    }

    /** 检测失败必须作为非法响应处理，不能降级为没有偏差。 */
    private void assertInvalid(NaturalPromptFidelityFindings findings, NaturalPromptDraft draft) {
        assertThatThrownBy(() -> findings.validate(input(), draft)).isInstanceOf(ProviderException.class);
    }

    /** 修订与检测逐项绑定，先拒绝范围变化，再保留既有正文与来源保护。 */
    private void assertRepairInvalid(NaturalPromptFidelityFindings findings, NaturalPromptFidelityReview review) {
        assertThatThrownBy(() -> findings.applyRepair(input(), draft(ORIGINAL), review)).isInstanceOf(ProviderException.class);
    }

    private NaturalPromptFidelityFindings.Finding finding(String source, String sourceQuote, String bodyQuote, String effect) {
        return new NaturalPromptFidelityFindings.Finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION,
                source, sourceQuote, bodyQuote, effect);
    }

    private NaturalPromptFidelityReview.Issue issue(NaturalPromptFidelityFindings.Finding finding, String replacement) {
        return new NaturalPromptFidelityReview.Issue(finding.type(), finding.sourceId(), finding.sourceQuote(), finding.bodyQuote(), replacement);
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test"), List.of(), List.of(), List.of(), "fixture:model").withStrategyVersion("18");
    }

    private NaturalPromptInput inputWithAnswer() {
        return new NaturalPromptInput(RAW, input().context(), List.of(new PlanAnswer("q1", "发送时间？", "时间未定")),
                List.of(), List.of(), "fixture:model").withStrategyVersion("18");
    }

    private NaturalPromptDraft draft(String body) { return draft(body, task("请写一条通知")); }

    private NaturalPromptDraft draft(String body, NaturalTaskUnderstanding understanding) {
        return new NaturalPromptDraft(body, NaturalPromptDraft.Framework.APE,
                new ProviderMetadata("fixture", "fixture", true, ""), understanding);
    }

    private NaturalTaskUnderstanding task(String anchor) {
        var requirement = new NaturalTaskUnderstanding.Requirement("通知正文",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "请写一条通知")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        return new NaturalTaskUnderstanding("写通知", "通知正文", List.of(requirement), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of(anchor))), List.of(), List.of()));
    }

    private NaturalTaskUnderstanding taskWithAnswer(String answerAnchor) {
        var original = task("请写一条通知");
        var requirements = new ArrayList<>(original.requirements());
        requirements.add(new NaturalTaskUnderstanding.Requirement("实际回答时间仍未定",
                List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "时间未定")),
                List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
        return new NaturalTaskUnderstanding(original.goal(), original.deliverable(), requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("请写一条通知")),
                                new NaturalTaskReview.Coverage(1, List.of(answerAnchor))), List.of(), List.of()));
    }
}
