package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 核对实际模板与确定性 Provider 的信息边界，不将本地断言冒充模型语义评测。 */
class TemplateGuidanceBoundaryTest {
    private final PromptTemplateRegistryImpl registry = new PromptTemplateRegistryImpl();

    @Test
    void shouldDeliverGuideWithoutConvertingUnmentionedCapabilitiesIntoAbsence() {
        String raw = "依据资料编写用户指南。资料未说明自动撤销；不得推断功能不存在。只写已核实操作。";
        var template = registry.resolve(TemplateCode.AUTO, raw);
        var context = new ContextSnapshot("已核实复制功能，其他能力未说明。", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "test");
        var result = new MockPromptEnhancementProvider().enhance(new EnhancementProviderRequest(raw, context, template,
                List.of(), List.of("不得编造事实。"), List.of(), new EnhancementOptions(TemplateCode.AUTO, false, true, false), null));
        assertThat(result.sections()).extracting("type").contains(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS).doesNotContain(PromptSectionType.EXAMPLES);
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content()).contains("未说明", "不存在");
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.TASK)
                .findFirst().orElseThrow().content()).contains("不得推断功能不存在");
    }

    @Test
    void shouldKeepExplicitResearchTemplateWithinTheRequestedDeliveryScope() {
        var template = registry.resolve(TemplateCode.RESEARCH_ANALYSIS, "对给定观点提出简明意见，只讨论用户给定的问题。");
        assertThat(template.code()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(template.outputGuidance()).contains("交付范围", "相关", "未决");
        assertThat(template.exampleGuidance()).contains("需要", "真实结果");
    }

    @Test
    void shouldPreserveSpecifiedFormattingWithoutDemandingAnotherReport() {
        var template = registry.resolve(TemplateCode.AUTO, "将中文段落翻译为英语，只输出译文，不附解释或示例。");
        assertThat(template.code()).isEqualTo(TemplateCode.GENERAL);
        assertThat(template.outputGuidance()).contains("不附解释");
        assertThat(template.acceptanceGuidance()).doesNotContain("接口", "软件测试", "Redis");
    }
}
