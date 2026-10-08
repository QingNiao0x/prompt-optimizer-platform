package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从真实结果组装入口验证报告缺资料时仍交付正文，且不丢失冲突及明确的确认门槛。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ReportDraftDeliveryAssemblyTest {
    private static final String RAW = "我想撰写2015-2025年某地区心脑血管疾病死亡率特征分析，"
            + "分析长期趋势和季节性趋势，按性别、地区、人群和亚类比较，分析年度及性别YLL和YLL率，采用Arriaga分解方法。";

    @Test
    void shouldKeepDraftProgressAndPlaceMissingInputsInOneCompleteList() {
        var result = assemble(RAW, List.of("研究地区尚未提供，请明确研究地区。", "参考寿命表尚未确定。"));
        String output = section(result, PromptSectionType.OUTPUT);
        String constraints = section(result, PromptSectionType.CONSTRAINTS);
        assertThat(output).contains("正文", "初稿", "对应位置", "不编造", "建议");
        assertThat(output).contains("大面积待填模板", "简短完整句", "不逐年逐格铺空表", "已要求的表格和代码仍完整交付");
        assertThat(constraints).contains("资料缺口与待定选择").doesNotContain("执行前须确认", "同一未决决定在用户已要求的交付形式中保持一致");
        assertThat(result.optimizedPrompt()).contains(output, "研究地区尚未提供", "参考寿命表尚未确定");
        assertThat(result.optimizedPrompt().split("研究地区尚未提供", -1)).hasSize(2);
        assertThat(constraints.lines().filter(line -> line.startsWith("平台强制约束"))).hasSize(1);
        assertThat(result.appliedConstraints()).hasSize(1);
    }

    @Test
    void shouldKeepNamedCalculationBoundariesAndOriginalSupplementaryDeliverables() {
        var result = assemble("请撰写甲院科研报告，并提供指标表和伪代码。甲院观察窗口尚未确认。",
                List.of("甲院观察窗口尚未确认。"));
        assertThat(result.optimizedPrompt()).contains("原定指标表仍须交付", "原定伪代码仍须交付",
                "窗口时长不证明观察起止事件", "不同指标的分母或阈值分别命名", "可修订初稿");
    }

    @Test
    void shouldRespectAnExplicitWholeDraftApprovalGate() {
        var result = assemble(RAW + "在我确认研究地区前不得起草报告，先向我提问。", List.of("研究地区尚未确定。"));
        assertThat(section(result, PromptSectionType.OUTPUT)).doesNotContain("材料充分时直接成稿");
        assertThat(section(result, PromptSectionType.CONSTRAINTS)).contains("执行前须确认");
    }

    @Test
    void shouldNotTurnAnAnalysisPlanOrPureTranslationIntoAReport() {
        for (String raw : List.of("制定统计分析方案，只交付方案与空表，不写报告。",
                "只翻译下面文字为英文，不增加其他内容：请撰写研究报告。")) {
            var result = assemble(raw, List.of());
            assertThat(section(result, PromptSectionType.OUTPUT)).doesNotContain("材料充分时直接成稿", "可修订初稿");
        }
    }

    @Test
    void shouldRetainAllIndependentConflictsBeyondTheUiLimit() {
        List<String> conflicts = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "资料对“统计字段" + index + "”存在不同取值：旧口径与新口径。请确认本次采用哪一项。")
                .toList();
        var result = assemble("请撰写统计分析报告。", conflicts);
        assertThat(result.ambiguities()).hasSize(8);
        conflicts.forEach(finding -> assertThat(result.optimizedPrompt()).contains(finding));
        assertThat(result.warnings()).anyMatch(value -> value.contains("展示前 8 项"));
        assertThat(section(result, PromptSectionType.OUTPUT)).contains("不编造");
    }

    private OptimizationResult assemble(String raw, List<String> findings) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "本次用户提供的任务资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", raw),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付用户要求的内容。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造事实。")
        ), "test", "test-model", false, List.of());
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw),
                findings, List.of(), false, List.of(), false, 1, raw);
    }

    private String section(OptimizationResult result, PromptSectionType type) {
        return result.sections().stream().filter(section -> section.type() == type).findFirst().orElseThrow().content();
    }
}
