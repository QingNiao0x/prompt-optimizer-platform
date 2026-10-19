package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.template.domain.PromptFrameworkSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证两种表达在同一任务及主框架上生成；不将策略文本断言当作真实作品质量验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PromptFrameworkGenerationContractTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "使用requests库爬取网页标题。",
            "只设计网页标题抓取方案，不编写实现代码。",
            "给合作方写简短邮件，请对方发送会议纪要，不超过100个汉字，不添加示例。",
            "将以下句子翻译成英文，只输出译文：请检查登录接口。",
            "从记录中提取日期，只输出严格JSON，禁止示例和虚构字段。",
            "先熟悉项目，核对现有模块职责，不修改代码。",
            "制定研究方案，方法可以推荐，实际评分参数由我确认。"
    })
    void presentationUsesSameFrameworkAndPreservesInput(String raw) {
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var selected = PromptFrameworkSelector.select(task);
        var natural = PromptRewriteStrategy.forTask(task, PromptFormat.COMPACT);
        var structured = PromptRewriteStrategy.forTask(task, PromptFormat.STRUCTURED);

        assertThat(natural.mode()).isEqualTo("NATURAL_FRAMEWORK");
        assertThat(structured.mode()).isEqualTo("FOCUSED_MODULES");
        assertThat(natural.instruction()).contains(selected.instruction(), "完整自然正文集中写入TASK",
                "放在适用动作附近", "BACKGROUND与OUTPUT保留空字符串", "引用目的段与平台保护不变");
        assertThat(structured.instruction()).contains(selected.instruction(), "模块职责", "约束集中表达业务边界");
        assertThat(natural.instruction()).isNotEqualTo(structured.instruction());
        assertThat(selected.instruction()).doesNotContain("自然段落顺序", "TASTE", "ALIGN");
        assertThat(task.effectivePrompt()).isEqualTo(raw);
        assertThat(task.examples().required()).isFalse();
    }

    @Test
    void authorizedExamplesAndUnresolvedParametersRemainIndependentOfFramework() {
        var task = EffectiveTaskViewFactory.create("制定研究方案，允许提供相关输入输出示例，但不编造研究数据。",
                TemplateCode.AUTO, List.of(new PlanAnswer("scale", "本研究采用什么实际评分尺度？", "尚未确定，由我确认。")),
                List.of(), true);
        var pending = task.pendingDecisions();
        for (var format : PromptFormat.values()) {
            var instruction = PromptRewriteStrategy.forTask(task, format).instruction();
            assertThat(instruction).contains("重要未决参数继续保持原状态", "示例按既有示例契约提供");
            assertThat(instruction).doesNotContain("默认五点", "Likert", "自然段落顺序：");
        }
        assertThat(task.pendingDecisions()).isEqualTo(pending).isNotEmpty();
    }
}
