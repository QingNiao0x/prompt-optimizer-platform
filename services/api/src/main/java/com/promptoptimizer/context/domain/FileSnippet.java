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
        boolean truncated
) {

    /**
     * 兼容尚未保存 summary 字段的旧历史记录。
     */
    public FileSnippet {
        summary = summary == null ? "" : summary;
    }
}
