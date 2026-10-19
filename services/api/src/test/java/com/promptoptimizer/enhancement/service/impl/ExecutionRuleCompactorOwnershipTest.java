package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确保普通去重不会拆碎完整输出契约，同时保留其他执行规则既有的去重行为。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExecutionRuleCompactorOwnershipTest {
    @Test
    void protectsAnEntireOutputEvenWhenOneSentenceAlreadyOccursInTheTask() {
        String shared = "每个结论应完整记录资料来源和适用范围。";
        String body = "提供比较结果表。" + shared;
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "比较两份资料。" + shared));
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", body));

        ExecutionRuleCompactor.compact(sections, Set.of(body));

        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo(body);
    }

    @Test
    void stillCompactsUnrelatedDuplicatesOutsideTheProtectedSource() {
        String repeated = "校验所有外部输入并返回清晰的错误信息。";
        String body = "交付带统计时间和来源的比较表。";
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "实现转换函数。\n" + repeated));
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", body + "\n" + repeated));

        ExecutionRuleCompactor.compact(sections, Set.of(body));

        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo(body);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains(repeated);
    }
}
