package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证精简只合并完整等值规则，不吞掉新对象、比较符、例外或真正未知。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExecutionRuleCompactorTest {
    /** 代码、表格和隐含主体不同的章节不参与文本去重，避免精简改变可执行逻辑。 */
    @Test
    void keepsCodeBlocksTablesAndRulesUnderDifferentBusinessHeadings() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        String source = "### 审批\n金额<=1000时返回AUTO，其他返回MANUAL。\n"
                + "### 退款\n金额<=1000时返回AUTO，其他返回MANUAL。\n"
                + "```java\nif (approval) { return amount<=1000 ? AUTO : MANUAL; }\n"
                + "if (approval) { return amount<=1000 ? AUTO : MANUAL; }\n```\n"
                + "| 来源 | 当期金额 |\n| 部门A | 保留原始记录，不修改其金额。 |\n"
                + "| 部门A | 保留原始记录，不修改其金额。 |";
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", source));
        ExecutionRuleCompactor.compact(sections);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(source);
    }

    @Test
    void removesOnlyIdenticalWholeRulesAndKeepsIndependentRequirements() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务",
                "审批金额<=1000时仍需保留原始记录。\n取消时保留有效的0与false，不修改表单。"));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束",
                "- 审批金额<=1000时仍需保留原始记录。\n- 退款金额<=1000时仍需保留原始记录。\n"
                        + "- 审批金额<1000时由另一个部门复核。\n- 授权期限尚未明确，不得擅自决定。"));
        ExecutionRuleCompactor.compact(sections);
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content())
                .doesNotContain("审批金额<=1000时仍需保留原始记录")
                .contains("退款金额<=1000", "审批金额<1000", "授权期限尚未明确");
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("0与false");
    }

    @Test
    void keepsRequiredSectionsNonemptyAndDoesNotRewriteUnknownsAsChoices() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var type : java.util.List.of(PromptSectionType.TASK, PromptSectionType.CONSTRAINTS)) {
            sections.put(type, new PromptSection(type, type.name(), "样本来源尚未确定，相关步骤需等待确认。"));
        }
        ExecutionRuleCompactor.compact(sections);
        assertThat(sections.values()).allSatisfy(section -> assertThat(section.content()).isNotBlank());
    }

    @Test
    void distinguishesSufficientTaskFromShortUnderspecifiedTaskWithoutSkippingValidation() {
        var policy = PromptRewriteStrategy.forPrompt("请为清笺撰写新闻稿。当前处于MVP。只输出两个标题和正文。"
                + "不得虚构用户数量，不生成商业承诺。");
        assertThat(policy.mode()).isEqualTo("MINIMAL_ORGANIZATION");
        assertThat(policy.instruction()).contains("未知", "明确限制", "四要素");
        assertThat(PromptRewriteStrategy.forPrompt("帮我写点东西").mode()).isEqualTo("CLARIFY_AND_STRUCTURE");
    }
}
