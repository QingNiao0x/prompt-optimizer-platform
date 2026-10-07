package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放模型抄写平台参数表后结果组装再次追加同表的真实反例。
 * 仅同一完整权威表可归并，不删指标交付表、其他对象、不同状态或引用中的原件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AuthoritativeParameterTableTest {
    private static final String TABLE = "当前参数依据（用于生成指标表，不代替指标表）：\n"
            + "| 参数 | 当前口径 | 状态与依据 |\n| --- | --- | --- |\n"
            + "| 甲院观察窗口 | 待确认 | 未决；无当前选值 |";
    private static final String GUIDANCE = "新增指标需保留独立参数依据。\n" + TABLE;

    @Test
    void retainsOneCompleteCopyOfTheCurrentPlatformTable() {
        String result = compact(TABLE + "\n\n" + GUIDANCE);
        assertThat(result.split("当前参数依据", -1)).hasSize(2);
        assertThat(result).contains(TABLE);
    }

    @Test
    void keepsTheActualIndicatorDeliverableAndDifferentParameterState() {
        String metric = "| 指标 | 分母 |\n| --- | --- |\n| 缺失率 | 待确认 |";
        String another = TABLE.replace("甲院", "乙院");
        String oldState = TABLE.replace("待确认", "候选24小时；未批准");
        String result = compact(metric + "\n\n" + another + "\n\n" + oldState + "\n\n" + GUIDANCE);
        assertThat(result).contains(metric, another, oldState, TABLE);
    }

    @Test
    void doesNotDeduplicateAQuotedOrFencedOriginalAgainstTheCurrentView() {
        for (String protectedCopy : java.util.List.of("```text\n" + TABLE + "\n```",
                "~~~text\n" + TABLE + "\n~~~", "> " + TABLE.replace("\n", "\n> "))) {
            assertThat(compact(protectedCopy + "\n\n" + GUIDANCE)).contains(protectedCopy, TABLE);
        }
    }

    @Test
    void preservesTheSameLiteralUnderASeparateBusinessHeading() {
        String scoped = "## 甲院2024年资料原件\n" + TABLE;
        assertThat(compact(scoped + "\n\n" + GUIDANCE)).contains(scoped, TABLE);
    }

    @Test
    void preservesAllRowsWhenAnotherSourceAddsANewCondition() {
        String conditional = TABLE + "\n| 乙院阈值 | 需2026年另行审批 | 未决；不得继承甲院 |";
        assertThat(compact(conditional + "\n\n" + GUIDANCE)).contains(conditional, TABLE);
    }

    @Test
    void neverLetsAFencedCopyConsumeTheCurrentGuidance() {
        String result = compact("~~~text\n" + GUIDANCE + "\n~~~\n\n" + GUIDANCE);
        assertThat(result.split("新增指标需保留独立参数依据", -1)).hasSize(3);
    }

    @Test
    void neverLetsAShortInnerFenceEndTheOriginalsProtection() {
        String original = "````text\n```\n" + GUIDANCE + "\n```\n````";
        String result = compact(original + "\n\n" + GUIDANCE);
        assertThat(result).contains(original);
        assertThat(result.split("新增指标需保留独立参数依据", -1)).hasSize(3);
    }

    private static String compact(String original) {
        var sections = new LinkedHashMap<PromptSectionType, PromptSection>();
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", original));
        AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.OUTPUT, GUIDANCE));
        return sections.get(PromptSectionType.OUTPUT).content();
    }
}
