package com.promptoptimizer.context.domain;

/**
 * 文件内容片段，可能因大小限制被截断。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record FileSnippet(
        String path,
        String language,
        String content,
        String summary,
        boolean truncated,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        MaterialPurpose purpose
) {

    /**
     * 兼容尚未保存 summary 字段的旧历史记录。
     */
    public FileSnippet {
        summary = summary == null ? "" : summary;
    }

    /** 兼容旧历史与既有调用方，未指定用途时依据原始提示词联合理解。 */
    public FileSnippet(String path, String language, String content, String summary, boolean truncated) {
        this(path, language, content, summary, truncated, null);
    }

    /** 模型及页面读取同一份用途说明，该说明不成为文件原文或用户执行授权。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String purposeGuidance() { return purpose == null ? null : purpose.guidance(); }
}
