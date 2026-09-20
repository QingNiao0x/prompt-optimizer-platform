package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.junit.jupiter.api.Test;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void shouldPreferModelAssessmentAndSynchronizeClarificationSection() {
        var response = response(List.of("订单取消后已支付款项应立即退款还是等待审核？",
                "订单取消后已支付款项应立即退款还是等待审核？"));
        var result = assemble(response, false);
        assertThat(result.ambiguities()).containsExactly("订单取消后已支付款项应立即退款还是等待审核？");
        assertThat(result.sections()).filteredOn(item -> item.type() == PromptSectionType.CLARIFICATIONS)
                .singleElement().satisfies(item -> assertThat(item.content()).isEqualTo("- " + result.ambiguities().getFirst()));
        assertThat(result.optimizedPrompt()).contains("平台强制约束", "不得削弱现有功能");
    }

    @Test
    void shouldRespectExplicitEmptyAssessmentAndPreservePlanConfirmedBehavior() {
        assertThat(assemble(response(List.of()), false).ambiguities()).isEmpty();
        assertThat(assemble(response(List.of()), false).sections()).extracting("type")
                .doesNotContain(PromptSectionType.CLARIFICATIONS);
        assertThat(assemble(response(List.of("是否立即退款？")), true).ambiguities()).isEmpty();
    }

    @Test
    void shouldReadLegacyClarificationsWithoutRestoringGenericWarnings() {
        var legacy = response(null);
        var result = assemble(legacy, false);
        assertThat(result.ambiguities()).containsExactly("历史订单是否也允许取消？");
    }

    @Test
    void shouldRejectInvalidOrSensitiveFindingsWithoutEchoingThem() {
        for (List<String> findings : List.of(List.of(" "), List.of("字".repeat(501)),
                java.util.Collections.nCopies(9, "具体业务问题？"), List.of("password=" + "secretvalue123456"),
                java.util.Arrays.asList((String) null))) {
            assertThatThrownBy(() -> assemble(response(findings), false))
                    .isInstanceOfSatisfying(ProviderException.class, error -> {
                        assertThat(error.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE);
                        assertThat(error.getMessage()).doesNotContain("secretvalue123456");
                    });
        }
    }

    private EnhancementProviderResponse response(List<String> findings) {
        return new EnhancementProviderResponse(List.of(
                section(PromptSectionType.BACKGROUND, "背景", "订单服务"),
                section(PromptSectionType.TASK, "任务", "新增订单取消功能"),
                section(PromptSectionType.OUTPUT, "输出", "实现及测试"),
                section(PromptSectionType.CONSTRAINTS, "约束", "保持兼容"),
                section(PromptSectionType.CLARIFICATIONS, "待确认", "- 历史订单是否也允许取消？\n- 尚未给出可验证的验收标准。")
        ), "test", "test", false, findings);
    }

    private com.promptoptimizer.enhancement.domain.OptimizationResult assemble(EnhancementProviderResponse response,
                                                                             boolean confirmed) {
        return assembler.assemble(response, emptyContext(),
                new PromptTemplate(TemplateCode.FEATURE_DEVELOPMENT, "输出", "测试通过", "示例"),
                List.of("尚未明确输入来源、参数格式或调用方式。"), List.of(), confirmed,
                List.of("不得削弱现有功能"), false, 1);
    }

    private ContextSnapshot emptyContext() {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
    }
}
