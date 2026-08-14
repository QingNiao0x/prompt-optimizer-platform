package com.promptoptimizer.template.application;

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
                ? inferTemplate(rawPrompt)
                : requestedTemplate;
        return templates.get(code);
    }

    /**
     * 根据原始需求关键词推断模板类型，未命中时默认功能开发。
     */
    private TemplateCode inferTemplate(String rawPrompt) {
        String prompt = rawPrompt.toLowerCase(Locale.ROOT);
        if (containsAny(prompt, "修复", "bug", "异常", "报错", "失败")) {
            return TemplateCode.BUG_FIX;
        }
        if (containsAny(prompt, "重构", "refactor", "整理代码", "拆分模块")) {
            return TemplateCode.REFACTORING;
        }
        if (containsAny(prompt, "测试", "test", "覆盖率", "用例")) {
            return TemplateCode.TESTING;
        }
        return TemplateCode.FEATURE_DEVELOPMENT;
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
