package com.promptoptimizer.history.controller;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.history.domain.*;
import com.promptoptimizer.history.dto.HistoryListQuery;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * V2统一历史入口，读取新旧结果并按已保存链路再次增强；存储、归属和逻辑删除共用原实现。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v2/optimization-history")
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentHistoryController {
    private final OptimizationHistoryService history;
    private final AnalyticsEventService analytics;

    public DocumentHistoryController(OptimizationHistoryService history, AnalyticsEventService analytics) {
        this.history = history;
        this.analytics = analytics;
    }

    /** 新旧结果在SQL分页前统一查询，UTC日期范围保持与原历史页面一致。 */
    @GetMapping
    public ApiResponse<OptimizationHistoryPage> list(HistoryListQuery query, HttpServletRequest request) {
        String keyword = query.keyword() == null ? null : query.keyword().strip();
        if (keyword != null && keyword.length() > 200) throw new InvalidOptimizationRequestException("原始提示词搜索条件过长。");
        OffsetDateTime from = null;
        OffsetDateTime until = null;
        if (query.dateRange() != null && !query.dateRange().isBlank()) {
            String[] dates = query.dateRange().split(",", -1);
            if (dates.length != 2) throw new InvalidOptimizationRequestException("创建时间范围格式无效。");
            try {
                LocalDate start = LocalDate.parse(dates[0].strip());
                LocalDate end = LocalDate.parse(dates[1].strip());
                if (end.isBefore(start)) throw new InvalidOptimizationRequestException("结束日期不能早于开始日期。");
                // 用户选择的是日历日期；包含结束日须转换为次日零点的排他上界，避免漏掉当天记录。
                from = start.atStartOfDay().atOffset(ZoneOffset.UTC);
                until = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException failure) {
                throw new InvalidOptimizationRequestException("创建时间范围格式无效，应使用 YYYY-MM-DD。");
            }
        }
        return ApiResponse.success(requestId(request), history.listAll(Math.max(1, query.current()),
                Math.max(1, Math.min(50, query.size())), keyword, from, until));
    }

    /** 详情中的正文直接来自持久化，不根据当前目录或模式重新生成。 */
    @GetMapping("/{id}")
    public ApiResponse<OptimizationHistoryDetail> get(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(requestId(request), history.getDocument(id));
    }

    /** 删除依然是带租户、工作区及未删除条件的逻辑删除。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        history.delete(id);
        return ApiResponse.success(requestId(request), null);
    }

    /** 新旧记录按服务端保存的版本分发，提交尝试和成功结果分开计数。 */
    @PostMapping("/{id}/re-optimize")
    public ApiResponse<DocumentReoptimizationResult> reoptimize(@PathVariable UUID id, HttpServletRequest request) {
        analytics.recordOptimizationSubmission(true, request);
        return ApiResponse.success(requestId(request), history.reoptimizeDocument(id));
    }

    /** 不调用模型；当前记录归属、编辑版本和固定权限均由服务端再次校验。 */
    @PatchMapping("/{id}/document")
    public ApiResponse<PromptTextEditResult> edit(@PathVariable UUID id,
            @Valid @RequestBody com.promptoptimizer.history.dto.PromptTextEditRequest input, HttpServletRequest request) {
        return ApiResponse.success(requestId(request), history.editText(id, input));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
