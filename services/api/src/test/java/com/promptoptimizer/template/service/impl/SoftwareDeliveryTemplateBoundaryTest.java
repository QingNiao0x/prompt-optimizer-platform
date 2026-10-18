package com.promptoptimizer.template.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对软件模板保持实现、方案与阅读的交付边界，不将库调用统一改成方案文档。
 * Mock 只证明模板指导进入本地响应，不代表真实模型作品已通过验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class SoftwareDeliveryTemplateBoundaryTest {
    private final PromptTemplateRegistryImpl registry = new PromptTemplateRegistryImpl();

    @ParameterizedTest
    @ValueSource(strings = {
            "使用requests库爬取网页标题。",
            "用httpx库请求网页并提取标题。",
            "Use requests to fetch a page title.",
            "请编写Python代码，使用requests库爬取网页标题。"
    })
    void implementationGuidanceRequiresUsableCodeRatherThanOnlyAPlan(String raw) {
        var template = registry.resolve(TemplateCode.AUTO, raw);
        assertThat(template.code()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(template.outputGuidance()).contains("交付可直接使用的代码", "不以方案文档代替");
        assertThat(template.outputGuidance()).doesNotStartWith("给出实现方案");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "给出登录功能的实现方案，不交付代码。",
            "为网页标题抓取功能制定实现方案，只输出方案文档。",
            "请设计登录接口的实现方案，先不修改代码。"
    })
    void softwarePlanningRequestsKeepTheirOriginalDeliverable(String raw) {
        var template = registry.resolve(TemplateCode.AUTO, raw);
        assertThat(template.code()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(template.outputGuidance()).contains("仅要求方案、文档或解释时保持相应范围");
        assertThat(template.acceptanceGuidance()).contains("按原定交付", "涉及修改时");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "仅解释使用requests库爬取网页标题的原理，不提供代码。",
            "只给出使用requests库爬取网页标题的思路，不要代码。",
            "根据爬虫源代码编写用户指南，不修改程序。",
            "先熟悉前后端项目，阅读源码并了解目录结构。",
            "整理参考资料。\n## 原文\n使用requests库爬取网页标题。",
            "翻译成英语，只输出译文。原文：使用requests库爬取网页标题。"
    })
    void explanationReadingAndQuotedSourceDoNotAcquireImplementationDeliverables(String raw) {
        var template = registry.resolve(TemplateCode.AUTO, raw);
        assertThat(template.code()).isEqualTo(TemplateCode.GENERAL);
        assertThat(template.outputGuidance()).doesNotContain("关键代码", "交付可直接使用的代码", "新增或修改的模块");
    }

    @Test
    void studyWithReproductionCodeKeepsTheStudyAsThePrimaryDeliverable() {
        String raw = "研究广东省死亡率长期趋势，并提供分析代码。";
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        var template = registry.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(intent.auxiliaryProfiles()).contains(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.engineeringConstraints()).isTrue();
        assertThat(template.code()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(template.outputGuidance()).contains("原定", "代码").doesNotContain("新增或修改的模块");
    }

    @Test
    void mockProjectsTheSameScopedGuidanceWithoutChangingTheOriginalRequest() {
        String raw = "使用requests库爬取网页标题。";
        var template = registry.resolve(TemplateCode.AUTO, raw);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var request = new EnhancementProviderRequest(raw, context, template, List.of(), List.of(), List.of(),
                EnhancementOptions.defaults());
        var response = new MockPromptEnhancementProvider().enhance(request);
        assertThat(response.sections()).filteredOn(section -> section.type() == PromptSectionType.TASK)
                .singleElement().extracting(section -> section.content()).isEqualTo(raw);
        assertThat(response.sections()).filteredOn(section -> section.type() == PromptSectionType.OUTPUT)
                .singleElement().extracting(section -> section.content())
                .asString().contains("交付可直接使用的代码", "不以方案文档代替");
        assertThat(response.sections()).noneMatch(section -> section.type() == PromptSectionType.EXAMPLES);
    }
}
