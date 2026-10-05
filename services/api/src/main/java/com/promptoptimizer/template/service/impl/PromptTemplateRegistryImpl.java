package com.promptoptimizer.template.service.impl;

import com.promptoptimizer.template.service.PromptTemplateRegistry;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 MVP 内置模板，并根据原始需求选择最合适的模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PromptTemplateRegistryImpl implements PromptTemplateRegistry {

    private final Map<TemplateCode, PromptTemplate> templates;

    /**
     * 初始化六类兼容模板，细分交付物由共享画像适配，不增加公开枚举。
     */
    public PromptTemplateRegistryImpl() {
        EnumMap<TemplateCode, PromptTemplate> values = new EnumMap<>(TemplateCode.class);
        values.put(TemplateCode.GENERAL, new PromptTemplate(
                TemplateCode.GENERAL,
                "围绕用户目标给出清晰的执行步骤、交付内容、必要依据和可复核结果。",
                "结果完整回应用户目标，明确事实、假设、限制与判断依据，并可直接使用。",
                "在示例有助于理解时，提供一个最小示例和对应结果。"
        ));
        values.put(TemplateCode.RESEARCH_ANALYSIS, new PromptTemplate(
                TemplateCode.RESEARCH_ANALYSIS,
                "按本次研究问题和交付范围说明相关对象、资料、方法及结果呈现要求；沿用已定口径，未决参数不默认取值，不增加未要求章节或实际分析。",
                "相关定义与比较条件可复核，资料可追溯，已定与未知分开；只检查本次交付的质量，不复述全套研究要求。",
                "仅在需要且用户允许时提供空表、图表格式或计算步骤示例，不冒充真实结果。"
        ));
        values.put(TemplateCode.FEATURE_DEVELOPMENT, new PromptTemplate(
                TemplateCode.FEATURE_DEVELOPMENT,
                "给出实现方案、需要新增或修改的模块、关键代码、接口契约和测试方案。",
                "功能满足正常流程、异常流程和边界场景，并且现有功能保持兼容。",
                "提供一个最小输入示例和对应的预期输出。"
        ));
        values.put(TemplateCode.BUG_FIX, new PromptTemplate(
                TemplateCode.BUG_FIX,
                "先定位根因和复现条件，再给出最小修复方案、影响范围和回归测试。",
                "问题可以稳定复现并被修复，相关回归场景通过，且不掩盖原始异常。",
                "给出修复前的复现输入和修复后的预期行为。"
        ));
        values.put(TemplateCode.REFACTORING, new PromptTemplate(
                TemplateCode.REFACTORING,
                "说明重构目标、保持不变的外部行为、分步修改方案和验证方式。",
                "对外行为和接口保持兼容，复杂度下降，现有测试和新增测试均通过。",
                "展示一个代表性的重构前后结构对比。"
        ));
        values.put(TemplateCode.TESTING, new PromptTemplate(
                TemplateCode.TESTING,
                "给出测试范围、测试用例、测试数据、Mock 边界和执行方式。",
                "覆盖正常、异常、边界和安全场景，测试可重复执行且不依赖真实生产数据。",
                "提供一个测试用例及其预期断言。"
        ));
        this.templates = Map.copyOf(values);
    }

    /**
     * 根据显式模板或本次肯定交付目标选择模板；附件词语不替代任务目标。
     *
     * @param requestedTemplate 用户选择的模板
     * @param rawPrompt 原始提示词
     * @return 已解析的内置模板
     */
    public PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt) {
        return resolve(requestedTemplate, rawPrompt, List.of());
    }

    /** 显式软件模板继续优先；自动和显式通用任务均可按已确认交付物细化。 */
    @Override
    public PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt, List<ConfirmedPlanDecision> decisions) {
        var intent = TaskIntentResolver.resolve(requestedTemplate, rawPrompt, decisions);
        TemplateCode code = intent.templateCode();
        PromptTemplate selected = templates.get(code);
        var profile = intent.deliveryProfile();
        if (!TaskIntentResolver.software(code) && profile != TaskDeliveryProfile.GENERAL) {
            return new PromptTemplate(code, profile.outputGuidance(), profile.acceptanceGuidance(), profile.exampleGuidance(), profile);
        }
        if (code == TemplateCode.GENERAL) {
            return new PromptTemplate(code, profile.outputGuidance(), profile.acceptanceGuidance(), profile.exampleGuidance(), profile);
        }
        return selected;
    }

    /**
     * 根据原始需求中的肯定交付目标推断内部生成策略，未命中时使用通用策略。
     */
    public TemplateCode infer(String rawPrompt) {
        return TaskIntentResolver.resolve(TemplateCode.AUTO, rawPrompt).templateCode();
    }
}
