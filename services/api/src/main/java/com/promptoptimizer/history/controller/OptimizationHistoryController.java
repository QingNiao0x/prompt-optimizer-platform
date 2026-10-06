package com.promptoptimizer.history.controller;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.history.dto.HistoryListQuery;
import com.promptoptimizer.history.service.OptimizationHistoryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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
    private static final int MAX_KEYWORD_LENGTH = 200;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final OptimizationHistoryService historyService;
    private final AnalyticsEventService analyticsEventService;

    /**
     * 注入历史服务及统计采集入口。
     */
    public OptimizationHistoryController(
            OptimizationHistoryService historyService,
            AnalyticsEventService analyticsEventService
    ) {
        this.historyService = historyService;
        this.analyticsEventService = analyticsEventService;
    }

    /**
     * 分页查询历史记录。
     */
    @GetMapping
    public ApiResponse<OptimizationHistoryPage> list(HistoryListQuery query, HttpServletRequest request) {
        int safeCurrent = Math.max(1, query.current());
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, query.size()));
        HistoryDateRange safeDateRange = parseDateRange(query.dateRange());
        return ApiResponse.success(
                requestId(request),
                historyService.list(
                        safeCurrent,
                        safeSize,
                        normalizeKeyword(query.keyword()),
                        safeDateRange.createdFrom(),
                        safeDateRange.createdToExclusive()
                )
        );
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

    /** 请求逻辑删除当前工作区内可访问的历史记录。 */
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
        // 与直接优化接口保持“提交请求”口径，记录尝试，不把模型失败伪装为成功。
        analyticsEventService.recordOptimizationSubmission(true, request);
        return ApiResponse.success(requestId(request), historyService.reoptimize(id));
    }

    /**
     * 从请求中读取由过滤器生成的请求标识。
     */
    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }

    /**
     * 统一清理关键字并限制查询长度，避免将空白筛选转换为全量模糊匹配。
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new InvalidOptimizationRequestException("原始提示词搜索条件过长。");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 解析前端日期范围。日期按 UTC 起止日处理，结束日期使用次日零点作为排他上界，覆盖整天记录。
     */
    private HistoryDateRange parseDateRange(String dateRange) {
        if (dateRange == null || dateRange.isBlank()) {
            return new HistoryDateRange(null, null);
        }
        String[] parts = dateRange.split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new InvalidOptimizationRequestException("创建时间范围格式无效。");
        }
        try {
            LocalDate from = LocalDate.parse(parts[0].trim(), DATE_FORMATTER);
            LocalDate to = LocalDate.parse(parts[1].trim(), DATE_FORMATTER);
            if (to.isBefore(from)) {
                throw new InvalidOptimizationRequestException("创建时间范围的结束日期不能早于开始日期。");
            }
            return new HistoryDateRange(
                    from.atStartOfDay().atOffset(ZoneOffset.UTC),
                    to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
            );
        } catch (DateTimeParseException exception) {
            throw new InvalidOptimizationRequestException("创建时间范围格式无效，应使用 YYYY-MM-DD。");
        }
    }

    private record HistoryDateRange(OffsetDateTime createdFrom, OffsetDateTime createdToExclusive) {
    }
}
