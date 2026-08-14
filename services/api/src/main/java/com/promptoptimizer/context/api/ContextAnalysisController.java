package com.promptoptimizer.context.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目上下文分析接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextAnalysisController {

    private final ContextAnalyzer contextAnalyzer;

    public ContextAnalysisController(ContextAnalyzer contextAnalyzer) {
        this.contextAnalyzer = contextAnalyzer;
    }

    /**
     * 分析用户主动提交的项目描述与文件内容，返回脱敏后的上下文快照。
     */
    @PostMapping("/analyze")
    public ApiResponse<ContextSnapshot> analyze(
            @Valid @RequestBody ContextAnalysisRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, contextAnalyzer.analyze(request));
    }
}
