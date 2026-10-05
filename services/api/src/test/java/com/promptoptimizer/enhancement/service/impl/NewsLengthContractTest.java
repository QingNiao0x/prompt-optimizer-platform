package com.promptoptimizer.enhancement.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证新闻正文的明确篇幅能进入可复制正文，固定结构和其他任务不受组织建议污染。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NewsLengthContractTest {
    /** 简短组织建议仍提供段落展开尺度，但不改写固定结构或用户原有验收范围。 */
    @Test
    void keepsUsefulParagraphScaleInMinimalOrganization() {
        assertThat(NewsLengthContract.conciseGuidance("撰写新闻稿正文600–800中文字符，标题另计。"))
                .contains("600–800中文字符", "每段约107中文字符", "不要只列一句要点", "组织建议")
                .doesNotContain("必须分成7段");
        assertThat(NewsLengthContract.conciseGuidance("撰写新闻稿正文600–800字，必须分成3段。"))
                .contains("600–800字").doesNotContain("个短段", "每段约");
    }

    @ParameterizedTest
    @ValueSource(strings = {"撰写新闻稿正文六百至八百个中文字符，另给两个标题。", "撰写新闻稿正文600–800中文字符，标题另计。"})
    void plansOnlyTheOriginalBodyRangeWithOptionalParagraphs(String raw) {
        assertThat(NewsLengthContract.guidance(raw)).contains("正文600–800中文字符", "约750中文字符", "约7个短段",
                "先按", "起草", "不把全部要点压进一个总括段",
                "组织建议", "不增加新的硬性验收", "不编造事实", "不附规划过程");
    }

    @Test
    void keepsAFixedParagraphCountAndDoesNotBorrowATitleBudget() {
        assertThat(NewsLengthContract.guidance("撰写新闻稿，标题15–25字，正文600–800字，必须分成3段。"))
                .contains("正文600–800字").doesNotContain("个短段");
        assertThat(NewsLengthContract.guidance("撰写新闻稿，标题15–25字，正文由执行者安排。" )).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"请只输出译文，正文600–800字。", "撰写新闻稿正文，只输出JSON，600–800字。",
            "撰写新闻稿，正文800–600字。", "撰写新闻稿，正文零至八百字。", "撰写新闻稿，正文十百至八百字。",
            "撰写新闻稿，正文-600至800字。", "撰写新闻稿，正文600–800字，另一正文900–1000字。",
            "撰写新闻稿正文，示例为600–800字，实际篇幅未确定。"})
    void doesNotInferUnsupportedConflictingOrInvalidBudgets(String raw) {
        assertThat(NewsLengthContract.guidance(raw)).isEmpty();
    }

    @Test
    void guidesAndSoftwareDoNotGainNewsRequirements() {
        assertThat(NewsLengthContract.guidance("为新用户写操作指南，正文600至800字。")).isEmpty();
        assertThat(NewsLengthContract.guidance("开发新闻稿接口，只输出代码，正文参数600至800字。")).isEmpty();
    }
}
