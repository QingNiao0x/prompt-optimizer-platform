package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 收敛平台推断契约的重复句，业务对象不同的章节仍保留。 */
class InferenceCompactionTest {
    @Test void platformInferenceHeadingDoesNotProtectItsOwnDuplicate() {
        String rule = "同一币种不等于已排除汇率影响。";
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束",
                rule + "\n推断依据：\n" + rule + "\n### 乙公司\n" + rule));
        AuthoritativeDeliveryCompactor.compact(sections, Map.of(PromptSectionType.CONSTRAINTS, rule));
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content().split(rule, -1)).hasSize(3);
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains("### 乙公司\n" + rule);
    }
}
