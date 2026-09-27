package com.promptoptimizer.template.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 管理 MVP 内置模板，并根据原始需求选择最合适的模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PromptTemplateRegistry {

    private final Map<TemplateCode, PromptTemplate> templates;

    /**
     * 初始化功能开发、Bug 修复、重构和测试四类内置模板。
     */
    public PromptTemplateRegistry() {
        EnumMap<TemplateCode, PromptTemplate> values = new EnumMap<>(TemplateCode.class);
        values.put(TemplateCode.GENERAL, new PromptTemplate(
                TemplateCode.GENERAL,
                "围绕用户目标给出清晰的执行步骤、交付内容、必要依据和可复核结果。",
                "结果完整回应用户目标，明确事实、假设、限制与判断依据，并可直接使用。",
                "在示例有助于理解时，提供一个最小示例和对应结果。"
        ));
        values.put(TemplateCode.RESEARCH_ANALYSIS, new PromptTemplate(
                TemplateCode.RESEARCH_ANALYSIS,
                "明确研究对象、时间与空间范围、数据来源、指标定义、分层方法、统计方法和结果呈现方式。",
                "研究口径可复现，数据来源可追溯，方法选择有依据，并说明缺失数据、偏倚和不确定性。",
                "提供分析表结构、图表清单或关键计算示例。"
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
     * 根据显式模板或需求关键词选择模板。
     *
     * @param requestedTemplate 用户选择的模板
     * @param rawPrompt 原始提示词
     * @return 已解析的内置模板
     */
    public PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt) {
        TemplateCode code = requestedTemplate == null || requestedTemplate == TemplateCode.AUTO
                ? infer(rawPrompt)
                : requestedTemplate;
        return templates.get(code);
    }

    /**
     * 根据原始需求关键词推断内部生成策略，未命中时使用通用策略。
     */
    public TemplateCode infer(String rawPrompt) {
        String prompt = rawPrompt.toLowerCase(Locale.ROOT);
        // 明确的软件交付动词优先于材料中的研究关键词。
        if (containsAny(prompt, "开发接口", "实现接口", "开发功能", "修复", "bug", "报错")
                || prompt.matches(".*开发.{0,12}(系统|平台|服务|应用|模块|工具).*")) {
            return containsAny(prompt, "修复", "bug", "报错")
                    ? TemplateCode.BUG_FIX : TemplateCode.FEATURE_DEVELOPMENT;
        }
        if (containsAny(prompt, "研究", "论文", "文献", "死亡率", "发病率", "时间序列", "回归分析", "统计分析", "arriaga", "yll")) {
            return TemplateCode.RESEARCH_ANALYSIS;
        }
        if (containsAny(prompt, "修复", "bug", "异常", "报错", "失败")) {
            return TemplateCode.BUG_FIX;
        }
        if (containsAny(prompt, "重构", "refactor", "整理代码", "拆分模块")) {
            return TemplateCode.REFACTORING;
        }
        if (containsAny(prompt, "测试", "test", "覆盖率", "用例")) {
            return TemplateCode.TESTING;
        }
        if (containsAny(prompt, "开发", "实现", "接口", "代码", "模块", "功能", "数据库", "前端", "后端")) {
            return TemplateCode.FEATURE_DEVELOPMENT;
        }
        return TemplateCode.GENERAL;
    }

    /**
     * 判断文本是否包含任一候选关键词。
     */
    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
