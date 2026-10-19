package com.promptoptimizer.history.domain;

import com.promptoptimizer.enhancement.domain.PromptDocument;
import java.util.UUID;

/**
 * V2 再次增强的结果与新记录标识；无历史模式下recordId为空，不伪造保存成功。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DocumentReoptimizationResult(UUID recordId, PromptDocument result) { }
