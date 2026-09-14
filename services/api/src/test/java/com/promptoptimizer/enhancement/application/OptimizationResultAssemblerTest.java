package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationResultAssemblerTest {

    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void shouldProduceFinalPromptFromConfirmedAnswersAndRemoveClarifications() {
        var result = assembler.assemble(
                new EnhancementProviderResponse(List.of(
                        section(PromptSectionType.BACKGROUND, "背景", "心脑血管疾病死亡率研究。"),
                        section(PromptSectionType.TASK, "任务", "完成趋势与分解分析。"),
                        section(PromptSectionType.OUTPUT, "输出", "输出表格和图表。"),
                        section(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。"),
                        section(PromptSectionType.CLARIFICATIONS, "待确认", "地区范围未知。")
                ), "mock", "model", true),
                emptyContext(),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可复现。", "示例"),
                List.of("地区范围未知。"),
                List.of(new PlanAnswer("research-region", "这项研究具体覆盖哪个地区？", "广东省")),
                true,
                List.of("说明数据来源。"),
                false,
                12
        );

        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.sections()).extracting("type")
                .doesNotContain(PromptSectionType.CLARIFICATIONS)
                .contains(PromptSectionType.ACCEPTANCE);
        assertThat(result.optimizedPrompt())
                .contains("广东省", "用户已确认的信息", "平台强制约束", "说明数据来源")
                .doesNotContain("待确认", "地区范围未知");
    }

    private PromptSection section(PromptSectionType type, String title, String content) {
        return new PromptSection(type, title, content);
    }

    private ContextSnapshot emptyContext() {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
    }
}
