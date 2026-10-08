package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已委托的未知呈现不重新选择；泛指题干也必须核对候选自己的机构、年份及审批证据。
 * 保留真正未知的执行参数，并用正向技术与批准依据防止推荐全部被清空。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanDelegationAndRecommendationScopeTest {
    private static final String RAW = "为甲院2025年拟定观察方案，只交付方案，不计算真实患者数据。"
            + "甲院2025年异常等待阈值尚未确定。最终交付主要取舍、独立未决条件及验收。";
    private static final String HANDLING = "甲院2025年异常等待阈值尚未确定，本次方案应如何处理这一未决参数？";

    @Test
    void doesNotReaskHowToPresentAPendingParameterInAProposalOnlyTask() {
        assertThat(filter(question(HANDLING, "", List.of()), RAW)).isEmpty();
    }

    @Test
    void preservesActualValuesNewDependenciesAndDifferentScopes() {
        for (String text : List.of("甲院2025年异常等待阈值是多少？", "甲院2025年观察起点采用什么事件？",
                "乙院2025年异常等待阈值尚未确定，本次方案应如何处理这一未决参数？",
                "甲院2026年异常等待阈值尚未确定，本次方案应如何处理这一未决参数？")) {
            assertThat(filter(question(text, "", List.of()), RAW)).as(text).hasSize(1);
        }
        assertThat(filter(question(HANDLING, "另外需确认跨院共享权限。", List.of()), RAW)).hasSize(1);
    }

    @Test
    void anExplicitUserRequestToChooseHandlingIsNotDelegated() {
        assertThat(filter(question(HANDLING, "", List.of()),
                RAW + "关于甲院2025年异常等待阈值，请让我选择未知时的处理方式。")).hasSize(1);
        assertThat(filter(question(HANDLING, "", List.of()), "为甲院2025年实施真实统计，处理方案尚未确定。"))
                .hasSize(1);
    }

    @Test
    void aGenericQuestionCannotRecommendEvidenceFromAnotherInstitutionOrYear() {
        for (String evidence : List.of("乙院2025年观察窗口采用24小时。该规则已批准并生效。",
                "甲院2024年观察窗口采用24小时。该规则已批准并生效。")) {
            assertThat(PlanRecommendationAligner.align(windowQuestion(), input("为甲院2025年拟定观察方案，请让我选择窗口。", evidence))
                    .options()).as(evidence).noneMatch(PlanOption::recommended);
        }
    }

    @Test
    void anApprovedMatchingPracticeRemainsARecommendationWithEvidence() {
        var result = PlanRecommendationAligner.align(windowQuestion(),
                input("为甲院2025年拟定观察方案，请让我选择窗口。",
                        "甲院2025年观察窗口采用24小时。该规则已批准并生效。"));
        assertThat(result.options()).filteredOn(PlanOption::recommended).singleElement().satisfies(option ->
                assertThat(option.recommendationReason()).contains("项目证据", "甲院2025年", "建议"));
    }

    @Test
    void unapprovedPracticeDoesNotBecomeAuthorityAndExplicitTechnicalPreferenceStillWorks() {
        assertThat(PlanRecommendationAligner.align(windowQuestion(), input("为甲院2025年拟定观察方案。",
                "甲院2025年观察窗口采用24小时。该规则尚未批准。"))
                .options()).noneMatch(PlanOption::recommended);
        var storage = new PlanQuestion("storage", "附件保存位置如何选择？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("minio", "MinIO", "复用现有存储", "本次采用MinIO。", false),
                        new PlanOption("local", "本地目录", "维护独立目录", "本次采用独立目录。", false)), List.of(), true);
        assertThat(PlanRecommendationAligner.align(storage, input("我偏好MinIO，但仍需确认本次选择。", "现有凭证服务使用MinIO。"))
                .options()).filteredOn(PlanOption::recommended).singleElement().satisfies(option -> {
                    assertThat(option.id()).isEqualTo("minio");
                    assertThat(option.recommendationReason()).contains("偏好", "建议");
                });
    }

    private static List<PlanQuestion> filter(PlanQuestion question, String raw) {
        return new PlanQuestionFilter().filter(List.of(question), input(raw, ""));
    }

    private static PlanQuestion question(String text, String hint, List<PlanOption> options) {
        return new PlanQuestion("parameter", text, hint, PlanQuestionType.FREE_TEXT, options, List.of(), true);
    }

    private static PlanQuestion windowQuestion() {
        return new PlanQuestion("window", "观察窗口建议采用哪种方案？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("24h", "24小时窗口", "观察窗口采用24小时。", "甲院2025年观察窗口采用24小时。", false),
                        new PlanOption("48h", "48小时窗口", "观察窗口采用48小时。", "甲院2025年观察窗口采用48小时。", false)),
                List.of(), true);
    }

    private static PlanningProviderRequest input(String raw, String evidence) {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                evidence.isBlank() ? List.of() : List.of(evidence), "COMPLETE", evidence.isBlank() ? 0 : 1, List.of());
        return new PlanningProviderRequest(raw, "", List.of(), digest);
    }
}
