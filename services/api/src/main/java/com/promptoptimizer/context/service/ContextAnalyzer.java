package com.promptoptimizer.context.service;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
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

    /**
     * 根据当前任务检索长文档中的相关片段。普通分析入口可继续调用单参数方法。
     */
    default ContextSnapshot analyze(ContextAnalysisRequest request, String query) {
        return analyze(request);
    }
}
