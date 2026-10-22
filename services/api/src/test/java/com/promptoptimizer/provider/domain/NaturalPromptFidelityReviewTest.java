package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 语义审阅只修正明确定位的片段，出处及覆盖仍由原协议核对；通过不代表语义判断自动正确。 */
class NaturalPromptFidelityReviewTest {
    private static final String RAW = "请写一条通知，只输出正文；活动是否举行和发送时间都未定。";
    private static final String BODY = "\r\n  请写一条通知，只输出正文。\n";

    @Test
    void noIssuesReturnsTheSameDraftWithoutChangingAnyBytes() {
        var draft = draft(BODY);
        var result = new NaturalPromptFidelityReview(List.of(), null).apply(input(), draft);
        assertThat(result).isSameAs(draft);
        assertThat(result.optimizedPrompt()).isEqualTo("\r\n  请写一条通知，只输出正文。\n");
    }

    @Test
    void noIssuesCannotReplaceTheExistingTaskRecord() {
        assertInvalid(new NaturalPromptFidelityReview(List.of(), task("请写一条通知")), draft(BODY));
    }

    @Test
    void missingIssuesOrMalformedEntriesNeverBecomeAPass() {
        assertInvalid(new NaturalPromptFidelityReview(null, null), draft(BODY));
        assertInvalid(new NaturalPromptFidelityReview(Arrays.asList((NaturalPromptFidelityReview.Issue) null),
                task("请写一条通知")), draft(BODY));
        for (var issue : List.of(
                new NaturalPromptFidelityReview.Issue(null, "rawPrompt", "请写一条通知", "请写一条通知", "请写通知"),
                issue("", "请写一条通知", "请写一条通知", "请写通知"),
                issue("rawPrompt", "", "请写一条通知", "请写通知"),
                issue("rawPrompt", "请写一条通知", "", "请写通知"),
                issue("rawPrompt", "请写一条通知", "请写一条通知", null))) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(issue), task("请写通知")), draft(BODY));
        }
    }

    @Test
    void multiplePatchesPreserveSurroundingBytesAndTheExistingMetadata() {
        String original = "  前缀\r\n请写一条通知。活动已经确认；发送时间为周五。\n  尾部空格  ";
        var draft = draft(original);
        var review = new NaturalPromptFidelityReview(List.of(
                issue("rawPrompt", "发送时间都未定", "发送时间为周五", "发送时间待定"),
                issue("rawPrompt", "活动是否举行和发送时间都未定", "活动已经确认", "活动待定")),
                task("请写一条通知"));
        var result = review.apply(input(), draft);
        assertThat(result.optimizedPrompt()).isEqualTo("  前缀\r\n请写一条通知。活动待定；发送时间待定。\n  尾部空格  ");
        assertThat(result.framework()).isSameAs(draft.framework());
        assertThat(result.provider()).isSameAs(draft.provider());
        assertThat(result.taskUnderstanding().requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        assertThat(draft.optimizedPrompt()).isEqualTo(original);
    }

    @Test
    void anExplicitDeletionRemovesOnlyItsLocatedFragment() {
        var review = new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "多余的已确认断言。", "")),
                task("请写一条通知"));
        assertThat(review.apply(input(), draft("请写一条通知。多余的已确认断言。\r\n尾注")).optimizedPrompt())
                .isEqualTo("请写一条通知。\r\n尾注");
    }

    @Test
    void patchesUseOriginalPositionsRatherThanMatchingInsertedTextAgain() {
        var review = new NaturalPromptFidelityReview(List.of(
                issue("rawPrompt", "请写一条通知", "甲值", "乙值"),
                issue("rawPrompt", "请写一条通知", "乙值", "待定")), task("请写一条通知"));
        assertThat(review.apply(input(), draft("请写一条通知。甲值；乙值。")).optimizedPrompt())
                .isEqualTo("请写一条通知。乙值；待定。");
    }

    @Test
    void aBodyQuoteMustExistExactlyOnceInTheOriginalDraft() {
        for (String quote : List.of("未出现的片段", "已确认")) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", quote, "待定")),
                    task("请写一条通知")), draft("请写一条通知。已确认，已确认。"));
        }
    }

    @Test
    void overlappingOrDuplicatePatchesAreRejected() {
        var first = issue("rawPrompt", "请写一条通知", "活动已经确认", "活动待定");
        for (var next : List.of(first, issue("rawPrompt", "请写一条通知", "已经确认", "尚未确定"))) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(first, next), task("请写一条通知")),
                    draft("请写一条通知。活动已经确认。"));
        }
    }

    @Test
    void aSourceIdCannotReferToTheDraftOrOtherModelMaterial() {
        for (String source : List.of("draft", "review", "actualAnswers.0", "context.fileSnippets.99")) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(issue(source, "请写一条通知", "已确认", "待定")),
                    task("请写一条通知")), draft("请写一条通知。已确认。"));
        }
    }

    @Test
    void aRealSourceIdStillNeedsItsOwnExactQuote() {
        assertInvalid(new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "活动已决定举办", "已确认", "待定")),
                task("请写一条通知")), draft("请写一条通知。已确认。"));
    }

    @Test
    void everyIssueFieldRejectsCredentials() {
        String secret = "password=fixture-only-credential";
        for (var issue : List.of(
                issue(secret, "请写一条通知", "已确认", "待定"),
                issue("rawPrompt", secret, "已确认", "待定"),
                issue("rawPrompt", "请写一条通知", secret, "待定"),
                issue("rawPrompt", "请写一条通知", "已确认", secret))) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(issue), task("请写一条通知")),
                    draft("请写一条通知。已确认。"));
        }
    }

    @Test
    void aReplacementCannotCompleteACredentialAcrossFragmentBoundaries() {
        var review = new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "xxxx", "fixture-only-credential")),
                task("请写一条通知"));
        assertInvalid(review, draft("请写一条通知。password=xxxx"));
    }

    @Test
    void replacementLengthIsBoundedWithoutTruncation() {
        var valid = new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "旧说明", "文".repeat(20_000))),
                task("请写一条通知"));
        assertThat(valid.apply(input(), draft("请写一条通知。旧说明")).optimizedPrompt()).hasSize(20_007);
        var invalid = new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "旧说明", "文".repeat(20_001))),
                task("请写一条通知"));
        assertInvalid(invalid, draft("请写一条通知。旧说明"));
    }

    @Test
    void everyReferenceFieldHasTheSameTwentyThousandCharacterLimit() {
        String longText = "文".repeat(20_001);
        for (var issue : List.of(
                issue(longText, "请写一条通知", "旧说明", "新说明"),
                issue("rawPrompt", longText, "旧说明", "新说明"),
                issue("rawPrompt", "请写一条通知", longText, "新说明"))) {
            assertInvalid(new NaturalPromptFidelityReview(List.of(issue), task("请写一条通知")), draft("请写一条通知。旧说明"));
        }
    }

    @Test
    void issueCapacityIsSixteenAndNeverSilentlyTruncated() {
        var issues = new ArrayList<NaturalPromptFidelityReview.Issue>();
        var body = new StringBuilder("请写一条通知。");
        for (int index = 0; index < 17; index++) {
            String fragment = "问题" + index + "。";
            issues.add(issue("rawPrompt", "请写一条通知", fragment, ""));
            body.append(fragment);
        }
        var allowed = new NaturalPromptFidelityReview(issues.subList(0, 16), task("请写一条通知"));
        assertThat(allowed.apply(input(), draft(body.toString())).optimizedPrompt()).isEqualTo("请写一条通知。问题16。");
        assertInvalid(new NaturalPromptFidelityReview(issues, task("请写一条通知")), draft(body.toString()));
    }

    @Test
    void aPatchNeedsAnUpdatedTaskRecordAndCoverageForThePatchedBody() {
        var patch = issue("rawPrompt", "活动是否举行和发送时间都未定", "活动已经确认", "活动待定");
        var original = draft("请写一条通知。活动已经确认。");
        assertInvalid(new NaturalPromptFidelityReview(List.of(patch), null), original);
        assertInvalid(new NaturalPromptFidelityReview(List.of(patch), task("活动已经确认")), original);
    }

    @Test
    void theUpdatedTaskRecordCannotInventItsOwnEvidence() {
        var original = task("请写一条通知");
        var wrong = new NaturalTaskUnderstanding("写通知", "通知正文", List.of(new NaturalTaskUnderstanding.Requirement("通知正文",
                List.of(new NaturalTaskUnderstanding.Evidence("review", "请写一条通知")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL))), List.of(), List.of(), original.review());
        assertInvalid(new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "已确认", "待定")), wrong),
                draft("请写一条通知。已确认。"));
    }

    @Test
    void aRevisionCannotOmitAnActualAnswerSource() {
        var bound = inputWithAnswer();
        var review = new NaturalPromptFidelityReview(List.of(issue("rawPrompt", "请写一条通知", "发送时间为周五", "发送时间待定")),
                task("请写一条通知"));
        assertThatThrownBy(() -> review.apply(bound, draft("请写一条通知。发送时间为周五。")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void aRevisionCanRetainBothTheOriginalRequestAndTheActualUnknownAnswer() {
        var original = task("请写一条通知");
        var requirements = new ArrayList<>(original.requirements());
        requirements.add(new NaturalTaskUnderstanding.Requirement("实际回答时间仍未定",
                List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "时间未定")),
                List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
        var updated = new NaturalTaskUnderstanding(original.goal(), original.deliverable(), requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(
                        new NaturalTaskReview.Coverage(0, List.of("请写一条通知")),
                        new NaturalTaskReview.Coverage(1, List.of("发送时间待定"))), List.of(), List.of()));
        var review = new NaturalPromptFidelityReview(List.of(issue("actualAnswers.0", "时间未定", "发送时间为周五", "发送时间待定")), updated);
        var result = review.apply(inputWithAnswer(), draft("请写一条通知。发送时间为周五。"));
        assertThat(result.optimizedPrompt()).isEqualTo("请写一条通知。发送时间待定。");
        assertThat(result.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test"), List.of(), List.of(), List.of(), "fixture:model").withStrategyVersion("4");
    }

    private NaturalPromptDraft draft(String body) {
        return new NaturalPromptDraft(body, NaturalPromptDraft.Framework.APE,
                new ProviderMetadata("fixture", "fixture", true, ""), task("请写一条通知"));
    }

    private NaturalPromptInput inputWithAnswer() {
        return new NaturalPromptInput(RAW, input().context(), List.of(new PlanAnswer("q1", "发送时间？", "时间未定")),
                List.of(), List.of(), "fixture:model").withStrategyVersion("4");
    }

    private NaturalPromptFidelityReview.Issue issue(String source, String quote, String bodyQuote, String replacement) {
        return new NaturalPromptFidelityReview.Issue(NaturalPromptFidelityReview.Type.STATE_PROMOTION,
                source, quote, bodyQuote, replacement);
    }

    private void assertInvalid(NaturalPromptFidelityReview review, NaturalPromptDraft draft) {
        assertThatThrownBy(() -> review.apply(input(), draft)).isInstanceOf(ProviderException.class);
    }

    private NaturalTaskUnderstanding task(String bodyAnchor) {
        var requirement = new NaturalTaskUnderstanding.Requirement("通知正文",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "请写一条通知")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        var review = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                List.of(new NaturalTaskReview.Coverage(0, List.of(bodyAnchor))), List.of(), List.of());
        return new NaturalTaskUnderstanding("写通知", "通知正文", List.of(requirement), List.of(), List.of(), review);
    }
}
