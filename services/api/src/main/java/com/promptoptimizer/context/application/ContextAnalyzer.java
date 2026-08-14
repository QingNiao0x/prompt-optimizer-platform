package com.promptoptimizer.context.application;

import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;

/**
 * 项目上下文分析能力接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface ContextAnalyzer {

    /**
     * 分析请求并返回截断和脱敏后的上下文快照。
     */
    ContextSnapshot analyze(ContextAnalysisRequest request);
}
