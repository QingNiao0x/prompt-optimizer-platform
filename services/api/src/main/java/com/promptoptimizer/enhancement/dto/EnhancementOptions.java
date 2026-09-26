package com.promptoptimizer.enhancement.dto;

import com.promptoptimizer.enhancement.domain.TemplateCode;

/**
 * 用户可调整的提示词增强选项。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EnhancementOptions(
        TemplateCode templateCode,
        Boolean includeConversationHistory,
        Boolean includePermissionBoundaries,
        Boolean includeExamples
) {

    /**
     * 为未显式传入的选项提供默认值。
     */
    public EnhancementOptions {
        templateCode = templateCode == null ? TemplateCode.AUTO : templateCode;
        includeConversationHistory = includeConversationHistory == null || includeConversationHistory;
        includePermissionBoundaries = includePermissionBoundaries == null || includePermissionBoundaries;
        includeExamples = includeExamples != null && includeExamples;
    }

    /**
     * 返回首发版本使用的默认增强选项。
     */
    public static EnhancementOptions defaults() {
        return new EnhancementOptions(TemplateCode.AUTO, true, true, false);
    }
}
