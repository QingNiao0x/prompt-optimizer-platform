package com.promptoptimizer.history.domain;

/**
 * 服务端实际保存的自然正文与编辑版本，供显示、复制、历史和撤销保持同一有效内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptTextEditResult(String optimizedPrompt, long editRevision) { }
