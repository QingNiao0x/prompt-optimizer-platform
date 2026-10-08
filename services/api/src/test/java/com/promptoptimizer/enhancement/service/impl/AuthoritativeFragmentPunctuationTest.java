package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台权威句的末尾句号不创建第二条要求，业务新条件、代码与独立范围不参与裁剪。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AuthoritativeFragmentPunctuationTest {
    private static final String RULE = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
    private static final String SHORT = RULE.substring(0, RULE.length() - 1);

    @Test
    void removesTheSameFullPlatformSentenceWithoutATerminalPeriod() {
        var sections = sections("交付物：\n- " + SHORT + "\n" + RULE,
                "保留全部未决条件。\n- " + SHORT);
        AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.OUTPUT, RULE));
        assertThat(sections.get(PromptSectionType.OUTPUT).content().split("共同分母覆盖未决指标", -1)).hasSize(2);
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).isEqualTo("保留全部未决条件。");
    }

    @Test
    void recognizesOnlyAnExactlyRegisteredBoldLineWithoutAPeriod() {
        for (String marker : List.of("**", "__")) {
            var sections = sections("交付物：\n" + marker + SHORT + marker + "\n" + RULE, "不得编造数据。");
            AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.OUTPUT, RULE));
            assertThat(sections.get(PromptSectionType.OUTPUT).content().split("共同分母覆盖未决指标", -1)).hasSize(2);
        }
    }

    @Test
    void preservesAdditionalConditionsAndForeignBusinessScope() {
        String additional = "- " + SHORT + "；乙院2026年新增指标须另行审批";
        String scoped = "### 乙院2026年\n" + SHORT;
        var sections = sections(RULE + "\n" + additional + "\n" + scoped, "保留独立决定。");
        AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.OUTPUT, RULE));
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).contains(additional, scoped);
    }

    @Test
    void doesNotTrimQuotedTablesCodeOrAnUnregisteredUserRule() {
        String protectedText = "> " + SHORT + "\n| 说明 |\n| " + SHORT + " |\n~~~text\n" + SHORT + "\n~~~";
        var sections = sections(RULE + "\n" + protectedText, "用户另行指定甲院2025年指标必须独立审批");
        AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.OUTPUT, RULE));
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).contains(protectedText);
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains("甲院2025年指标必须独立审批");
    }

    private static EnumMap<PromptSectionType, PromptSection> sections(String output, String constraints) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", output));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints));
        return sections;
    }
}
