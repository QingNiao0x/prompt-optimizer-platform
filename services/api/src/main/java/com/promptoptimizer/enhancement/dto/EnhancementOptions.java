package com.promptoptimizer.enhancement.dto;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.PromptFormat;

/**
 * 用户可调整的提示词增强选项。
 * 已移除的示例开关仅在旧请求反序列化时忽略，不进入模型输入或新历史。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties("includeExamples")
public record EnhancementOptions(
        TemplateCode templateCode,
        Boolean includeConversationHistory,
        Boolean includePermissionBoundaries,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        PromptFormat promptFormat
) {

    /**
     * 为未显式传入的选项提供默认值。
     */
    public EnhancementOptions {
        templateCode = templateCode == null ? TemplateCode.AUTO : templateCode;
        includeConversationHistory = includeConversationHistory == null || includeConversationHistory;
        includePermissionBoundaries = includePermissionBoundaries == null || includePermissionBoundaries;
    }

    /** 缺省生成模式沿用旧请求的自动判断，不将未设置误当作用户已选择分节。 */
    public EnhancementOptions(TemplateCode templateCode, Boolean includeConversationHistory,
                              Boolean includePermissionBoundaries) {
        this(templateCode, includeConversationHistory, includePermissionBoundaries, null);
    }

    /**
     * 返回首发版本使用的默认增强选项。
     */
    public static EnhancementOptions defaults() {
        return new EnhancementOptions(TemplateCode.AUTO, true, true);
    }
}
