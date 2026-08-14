package com.promptoptimizer.history.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.history.application.OptimizationHistoryService;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化历史 REST 接口，提供分页列表、详情、删除和重新优化。
 */
@RestController
@RequestMapping("/api/v1/optimization-history")
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationHistoryController {

    private static final int MAX_PAGE_SIZE = 50;

    private final OptimizationHistoryService historyService;

    /**
     * 注入历史服务。
     */
    public OptimizationHistoryController(OptimizationHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * 分页查询历史记录。
     */
    @GetMapping
    public ApiResponse<OptimizationHistoryPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        return ApiResponse.success(requestId(request), historyService.list(safePage, safeSize));
    }

    /**
     * 查询单条历史记录详情。
     */
    @GetMapping("/{id}")
    public ApiResponse<OptimizationHistoryDetail> get(
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.success(requestId(request), historyService.get(id));
    }

    /**
     * 删除单条历史记录。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        historyService.delete(id);
        return ApiResponse.success(requestId(request), null);
    }

    /**
     * 使用保存的原始输入再次优化，并返回新记录和结果。
     */
    @PostMapping("/{id}/re-optimize")
    public ApiResponse<ReoptimizationResult> reoptimize(
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.success(requestId(request), historyService.reoptimize(id));
    }

    /**
     * 从请求中读取由过滤器生成的请求标识。
     */
    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
