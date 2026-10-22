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
 * 拟议的诊断与修订目标分离契约：同一首稿片段可有多个来源和问题，但只能编辑一次。
 * 本文件仅供设计审阅；未接入正式测试目录，也未宣称模型判断或修订语义已经通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptFidelityTargetsTest {
    private static final String RAW = "请核查近期修改是否已部署，给出核查依据；本次只核查，不进行部署。";
    private static final String ANSWER = "本次不允许部署到本地测试环境，生产环境也不得操作。";
    private static final String BAD = "用户已确认将近期修改部署到生产环境，再检查是否生效。";
    private static final String PREFIX = "  请核查近期修改是否已部署。\r\n";
    private static final String SUFFIX = "\n尾部空格  ";
    private static final String BODY = PREFIX + BAD + SUFFIX;
    private static final String CORRECTED = "本次不允许部署到本地测试环境，生产环境也不得操作。";

    @Test
    void noDiagnosesProduceNoTargetsAndCannotReplaceTheExistingDraft() {
        var findings = new NaturalPromptFidelityFindings(List.of());
        var original = draft(BODY);
        assertThat(NaturalPromptFidelityTargets.resolve(input(), original, findings)).isEmpty();
        assertThat(NaturalPromptFidelityTargets.applyRepair(input(), original, findings,
                new NaturalPromptFidelityTargets.Repair(List.of(), null))).isSameAs(original);
        assertThatThrownBy(() -> NaturalPromptFidelityTargets.applyRepair(input(), original, findings,
                new NaturalPromptFidelityTargets.Repair(List.of(), task("用户已确认"))))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void twoRealProblemsOnTheSameSpanRetainBothDiagnosesAndUseOneEdit() {
        var findings = sharedSpanFindings();
        var original = draft(BODY);
        var targets = NaturalPromptFidelityTargets.resolve(input(), original, findings);
        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().index()).isZero();
        assertThat(targets.getFirst().bodyQuote()).isEqualTo(BAD);
        assertThat(targets.getFirst().diagnosisIndices()).containsExactly(0, 1);
        assertThat(findings.issues()).extracting(NaturalPromptFidelityFindings.Finding::sourceId)
                .containsExactly("rawPrompt", "actualAnswers.0");
        assertThat(findings.issues()).extracting(NaturalPromptFidelityFindings.Finding::type)
                .containsExactly(NaturalPromptFidelityReview.Type.AUTHORITY_EXPANSION, NaturalPromptFidelityReview.Type.STATE_PROMOTION);

        var repaired = NaturalPromptFidelityTargets.applyRepair(input(), original, findings,
                new NaturalPromptFidelityTargets.Repair(List.of(new NaturalPromptFidelityTargets.Edit(0, CORRECTED)),
                        task("本次不允许部署到本地测试环境")));
        assertThat(repaired.optimizedPrompt()).isEqualTo(PREFIX + CORRECTED + SUFFIX);
        assertThat(repaired.framework()).isSameAs(original.framework());
        assertThat(repaired.provider()).isSameAs(original.provider());
        assertThat(original.optimizedPrompt()).isEqualTo(BODY);
        assertThat(findings.issues()).hasSize(2);
    }

    @Test
    void groupingDoesNotSkipValidationOfASecondIndependentSource() {
        var first = sharedSpanFindings().issues().getFirst();
        for (var invalidSecond : List.of(
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "fidelityFindings", ANSWER, BAD, "建议被当成用户授权"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", "用户确认可部署到生产", BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "rawPrompt", ANSWER, BAD, "跨来源引文被错配"))) {
            assertResolveInvalid(new NaturalPromptFidelityFindings(List.of(first, invalidSecond)), draft(BODY));
        }
    }

    @Test
    void groupingPreservesAllSourcesEvenWhenSeveralDiagnosesShareOneTarget() {
        var first = sharedSpanFindings().issues().getFirst();
        var second = sharedSpanFindings().issues().get(1);
        var third = finding(NaturalPromptFidelityReview.Type.CONDITION_CHANGE, "rawPrompt", "本次只核查，不进行部署", BAD,
                "只核查的否定条件被替换为先执行部署");
        var findings = new NaturalPromptFidelityFindings(List.of(first, second, third));
        assertThat(NaturalPromptFidelityTargets.resolve(input(), draft(BODY), findings).getFirst().diagnosisIndices())
                .containsExactly(0, 1, 2);
        assertThat(findings.issues().get(1).sourceQuote()).isEqualTo(ANSWER);
        assertThat(findings.issues().get(2).sourceQuote()).isEqualTo("本次只核查，不进行部署");
    }

    @Test
    void targetIndicesFollowFirstDiagnosisOccurrenceRatherThanBodyOrder() {
        String body = PREFIX + BAD + "另写周五完成。" + SUFFIX;
        var firstInList = finding(NaturalPromptFidelityReview.Type.UNSUPPORTED_FACT, "rawPrompt", "给出核查依据", "另写周五完成。",
                "首稿新增没有来源的完成日期");
        var findings = new NaturalPromptFidelityFindings(List.of(firstInList,
                sharedSpanFindings().issues().getFirst(), sharedSpanFindings().issues().get(1)));
        var targets = NaturalPromptFidelityTargets.resolve(input(), draft(body), findings);
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).index()).isZero();
        assertThat(targets.get(0).bodyQuote()).isEqualTo("另写周五完成。");
        assertThat(targets.get(0).diagnosisIndices()).containsExactly(0);
        assertThat(targets.get(1).index()).isEqualTo(1);
        assertThat(targets.get(1).bodyQuote()).isEqualTo(BAD);
        assertThat(targets.get(1).diagnosisIndices()).containsExactly(1, 2);
        var repaired = NaturalPromptFidelityTargets.applyRepair(input(), draft(body), findings,
                new NaturalPromptFidelityTargets.Repair(List.of(
                        new NaturalPromptFidelityTargets.Edit(1, CORRECTED), new NaturalPromptFidelityTargets.Edit(0, "")),
                        task("本次不允许部署到本地测试环境")));
        assertThat(repaired.optimizedPrompt()).isEqualTo(PREFIX + CORRECTED + SUFFIX);
    }

    @Test
    void partialOverlapsStillFailInsteadOfBeingMergedIntoABroaderEdit() {
        var first = sharedSpanFindings().issues().getFirst();
        var overlapping = finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER,
                "已确认将近期修改部署到生产环境", "真实回答被反转");
        assertResolveInvalid(new NaturalPromptFidelityFindings(List.of(first, overlapping)), draft(BODY));
    }

    @Test
    void theSameQuoteMustStillHaveOneUniquePositionInTheDraft() {
        assertResolveInvalid(sharedSpanFindings(), draft(PREFIX + BAD + BAD + SUFFIX));
        assertResolveInvalid(sharedSpanFindings(), draft(PREFIX + "另一段正文。" + SUFFIX));
    }

    @Test
    void editsMustCoverEveryResolvedTargetExactlyOnce() {
        var findings = sharedSpanFindings();
        for (var edits : List.of(List.<NaturalPromptFidelityTargets.Edit>of(),
                List.of(new NaturalPromptFidelityTargets.Edit(0, CORRECTED), new NaturalPromptFidelityTargets.Edit(0, CORRECTED)),
                List.of(new NaturalPromptFidelityTargets.Edit(-1, CORRECTED)),
                List.of(new NaturalPromptFidelityTargets.Edit(1, CORRECTED)),
                List.of(new NaturalPromptFidelityTargets.Edit(0, CORRECTED), new NaturalPromptFidelityTargets.Edit(1, "新增片段")))) {
            assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(edits, task("本次不允许部署到本地测试环境")));
        }
    }

    @Test
    void missingRepairOrMissingEditDataNeverBecomesAnUnchangedSuccess() {
        var findings = sharedSpanFindings();
        assertRepairInvalid(findings, null);
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(null, task("本次不允许部署到本地测试环境")));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(
                Arrays.asList((NaturalPromptFidelityTargets.Edit) null), task("本次不允许部署到本地测试环境")));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(
                List.of(new NaturalPromptFidelityTargets.Edit(null, CORRECTED)), task("本次不允许部署到本地测试环境")));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(
                List.of(new NaturalPromptFidelityTargets.Edit(0, null)), task("本次不允许部署到本地测试环境")));
    }

    @Test
    void aNewSpanCannotBeIntroducedThroughAnAdditionalTargetIndex() {
        var repair = new NaturalPromptFidelityTargets.Repair(List.of(new NaturalPromptFidelityTargets.Edit(0, CORRECTED),
                new NaturalPromptFidelityTargets.Edit(42, "把未诊断的尾部改掉")), task("本次不允许部署到本地测试环境"));
        assertRepairInvalid(sharedSpanFindings(), repair);
    }

    @Test
    void diagnosisCapacityCountsAllDiagnosesRatherThanOnlyGroupedTargets() {
        var diagnoses = new ArrayList<NaturalPromptFidelityFindings.Finding>();
        for (int index = 0; index < 17; index++) {
            diagnoses.add(finding(NaturalPromptFidelityReview.Type.AUTHORITY_EXPANSION, "rawPrompt", "本次只核查，不进行部署", BAD,
                    "诊断" + index + "：核查被扩大为部署"));
        }
        var allowed = new NaturalPromptFidelityFindings(diagnoses.subList(0, 16));
        assertThat(NaturalPromptFidelityTargets.resolve(input(), draft(BODY), allowed)).hasSize(1);
        assertThat(NaturalPromptFidelityTargets.resolve(input(), draft(BODY), allowed).getFirst().diagnosisIndices())
                .hasSize(16).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertResolveInvalid(new NaturalPromptFidelityFindings(diagnoses), draft(BODY));
    }

    @Test
    void everyGroupedDiagnosisKeepsItsOwnSecretAndLengthChecks() {
        var first = sharedSpanFindings().issues().getFirst();
        String secret = "password=fixture-only-credential";
        for (var second : List.of(
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, secret, ANSWER, BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", secret, BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, secret, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD, secret),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "文".repeat(20_001), ANSWER, BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", "文".repeat(20_001), BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, "文".repeat(20_001), "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD, "文".repeat(20_001)))) {
            assertResolveInvalid(new NaturalPromptFidelityFindings(List.of(first, second)), draft(BODY));
        }
        var longValid = finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD, "文".repeat(20_000));
        assertThat(NaturalPromptFidelityTargets.resolve(input(), draft(BODY), new NaturalPromptFidelityFindings(List.of(first, longValid))))
                .hasSize(1);
    }

    @Test
    void groupingDoesNotPermitNullOrBlankDiagnosisFields() {
        var first = sharedSpanFindings().issues().getFirst();
        for (var invalid : List.of(
                finding(null, "actualAnswers.0", ANSWER, BAD, "真实回答被反转"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD, " "),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD, null))) {
            assertResolveInvalid(new NaturalPromptFidelityFindings(List.of(first, invalid)), draft(BODY));
        }
        assertResolveInvalid(new NaturalPromptFidelityFindings(null), draft(BODY));
        assertResolveInvalid(new NaturalPromptFidelityFindings(Arrays.asList(first, null)), draft(BODY));
    }

    @Test
    void aGroupedRepairStillRequiresCoverageForTheFinalBodyAndAllActualAnswers() {
        var findings = sharedSpanFindings();
        var edit = List.of(new NaturalPromptFidelityTargets.Edit(0, CORRECTED));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(edit, null));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(edit, task("用户已确认")));
        assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(edit, taskWithoutAnswer()));
        var repaired = NaturalPromptFidelityTargets.applyRepair(input(), draft(BODY), findings,
                new NaturalPromptFidelityTargets.Repair(edit, task("本次不允许部署到本地测试环境")));
        assertThat(repaired.taskUnderstanding().requirements()).hasSize(2);
        assertThat(repaired.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
    }

    @Test
    void replacementSafetyAndCapacityAreCheckedAfterGrouping() {
        var findings = sharedSpanFindings();
        for (String replacement : List.of("password=fixture-only-credential", "文".repeat(20_001))) {
            assertRepairInvalid(findings, new NaturalPromptFidelityTargets.Repair(
                    List.of(new NaturalPromptFidelityTargets.Edit(0, replacement)), task("本次不允许部署到本地测试环境")));
        }
    }

    @Test
    void theExistingReviewAndFindingsProtocolsContinueRejectingDuplicateSpans() {
        var findings = sharedSpanFindings();
        assertThatThrownBy(() -> findings.validate(input(), draft(BODY))).isInstanceOf(ProviderException.class);
        var issues = findings.issues().stream().map(finding -> new NaturalPromptFidelityReview.Issue(finding.type(),
                finding.sourceId(), finding.sourceQuote(), finding.bodyQuote(), CORRECTED)).toList();
        assertThatThrownBy(() -> new NaturalPromptFidelityReview(issues, task("本次不允许部署到本地测试环境"))
                .apply(input(), draft(BODY))).isInstanceOf(ProviderException.class);
    }

    /** 两份独立来源分别证明核查越权和“用户已确认”状态反转，共用一个不可分割的原句。 */
    private NaturalPromptFidelityFindings sharedSpanFindings() {
        return new NaturalPromptFidelityFindings(List.of(
                finding(NaturalPromptFidelityReview.Type.AUTHORITY_EXPANSION, "rawPrompt", "本次只核查，不进行部署", BAD,
                        "原要求只核查，首稿要求先部署到生产环境"),
                finding(NaturalPromptFidelityReview.Type.STATE_PROMOTION, "actualAnswers.0", ANSWER, BAD,
                        "真实回答明确禁止操作，首稿反写成用户已经确认")));
    }

    private NaturalPromptFidelityFindings.Finding finding(NaturalPromptFidelityReview.Type type, String source,
            String sourceQuote, String bodyQuote, String effect) {
        return new NaturalPromptFidelityFindings.Finding(type, source, sourceQuote, bodyQuote, effect);
    }

    private void assertResolveInvalid(NaturalPromptFidelityFindings findings, NaturalPromptDraft draft) {
        assertThatThrownBy(() -> NaturalPromptFidelityTargets.resolve(input(), draft, findings)).isInstanceOf(ProviderException.class);
    }

    private void assertRepairInvalid(NaturalPromptFidelityFindings findings, NaturalPromptFidelityTargets.Repair repair) {
        assertThatThrownBy(() -> NaturalPromptFidelityTargets.applyRepair(input(), draft(BODY), findings, repair))
                .isInstanceOf(ProviderException.class);
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test"), List.of(new PlanAnswer("q_scope", "本次允许什么部署范围？", ANSWER)),
                List.of(), List.of(), "fixture:model").withStrategyVersion("18");
    }

    private NaturalPromptDraft draft(String body) {
        return new NaturalPromptDraft(body, NaturalPromptDraft.Framework.APE,
                new ProviderMetadata("fixture", "fixture", true, ""), task("用户已确认"));
    }

    /** 保留原始核查任务与绑定回答两份来源，供组装前后覆盖核对。 */
    private NaturalTaskUnderstanding task(String answerAnchor) {
        var goal = new NaturalTaskUnderstanding.Requirement("核查近期修改是否已部署",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "请核查近期修改是否已部署")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        var answer = new NaturalTaskUnderstanding.Requirement("按真实回答保留禁止部署的范围",
                List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", ANSWER)),
                List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION));
        return new NaturalTaskUnderstanding("核查部署状态", "核查依据", List.of(goal, answer), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("请核查近期修改是否已部署")),
                                new NaturalTaskReview.Coverage(1, List.of(answerAnchor))), List.of(), List.of()));
    }

    private NaturalTaskUnderstanding taskWithoutAnswer() {
        var full = task("本次不允许部署到本地测试环境");
        return new NaturalTaskUnderstanding(full.goal(), full.deliverable(), List.of(full.requirements().getFirst()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("请核查近期修改是否已部署"))), List.of(), List.of()));
    }
}
