package com.promptoptimizer.policy.service;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;

import java.util.List;

/**
 * 在上下文进入模型前剔除受保护路径。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface ProtectedContextFilter {

    /** 从请求中移除受保护路径，并返回被拦截的路径。 */
    FilteredContext filter(ContextAnalysisRequest request, PermissionPolicyInput permissionPolicy);

    /** 把本次过滤结果附加到上下文摘要。 */
    ContextSnapshot attachReport(ContextSnapshot snapshot, FilteredContext filtered);

    /**
     * 可继续分析的请求及本次被拦截的受保护路径。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    record FilteredContext(ContextAnalysisRequest request, List<String> protectedPaths) {

        public FilteredContext {
            protectedPaths = List.copyOf(protectedPaths);
        }
    }
}
