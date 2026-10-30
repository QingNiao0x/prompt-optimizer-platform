package com.promptoptimizer.analytics.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.PeriodView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.TokenRankingView;
import com.promptoptimizer.analytics.dto.ModelCallQuery;
import com.promptoptimizer.analytics.dto.TokenRankingQuery;
import com.promptoptimizer.analytics.mapper.ModelCallAnalyticsMapper;
import com.promptoptimizer.analytics.service.AdminModelCallAnalyticsService;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.common.logging.ModelCallObservation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 匿名租户级模型计量查询；不缓存审计数据，不将缺失用量估算为供应商账单。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class AdminModelCallAnalyticsServiceImpl implements AdminModelCallAnalyticsService {
    private final AnalyticsPeriodResolver periods;
    private final ModelCallAnalyticsMapper mapper;

    /** 与现有统计模块一致，允许无数据库装配模式启动但查询时明确报告不可用。 */
    public AdminModelCallAnalyticsServiceImpl(AnalyticsPeriodResolver periods, ObjectProvider<ModelCallAnalyticsMapper> mapper) {
        this.periods = periods;
        this.mapper = mapper.getIfAvailable();
    }

    /** 使用统计时区展开完整周期，所有排序键来自固定白名单而非动态 SQL。 */
    @Override
    @Transactional(readOnly = true)
    public TokenRankingView tokenRanking(TokenRankingQuery query) {
        String scope = code(query.scope(), "GLOBAL", Set.of("GLOBAL", "TENANT"), "scope");
        UUID tenant = tenant(scope, query.tenantId());
        String group = code(query.groupBy(), "OPERATION", Set.of("OPERATION", "SOURCE"), "groupBy");
        int limit = bounded(query.limit(), 20, 100, "limit");
        AnalyticsPeriod period = periods.resolveRanking(query.period(), query.date());
        return new TokenRankingView(view(period), scope, tenant, group, requireMapper().tokenRanking(period, tenant, group, limit));
    }

    /** 用同一只读快照生成明细与总数；日期两端分别转换当地午夜以保留 DST 边界。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelCallPage modelCalls(ModelCallQuery query) {
        String scope = code(query.scope(), "GLOBAL", Set.of("GLOBAL", "TENANT"), "scope");
        UUID tenant = tenant(scope, query.tenantId());
        int current = bounded(query.current(), 1, 100000, "current");
        int size = bounded(query.size(), 20, 100, "size");
        String operation = query.operation();
        // 已保存的未知历史记录仍允许筛选；业务代码与采集端共享，避免新增工具在查询端被误拒。
        if (operation != null && !"unknown".equals(operation) && !ModelCallObservation.operations().contains(operation)) {
            throw new InvalidOptimizationRequestException("operation 不是可查询的模型操作代码");
        }
        String requestId = query.requestId();
        if (requestId != null && (requestId.length() > 64 || !requestId.matches("[A-Za-z0-9._-]+"))) {
            throw new InvalidOptimizationRequestException("requestId 格式无效");
        }
        AnalyticsPeriod period = periods.resolve("CUSTOM", query.fromDate(), query.toDate());
        Page<ModelCallView> page = new Page<>(current, size);
        page.setCountId("countModelCalls");
        var result = requireMapper().selectModelCalls(page, period, tenant, operation,
                requestId == null ? null : ModelCallObservation.safeRequestId(requestId));
        var outcomes = requireMapper().requestOutcomes(period, tenant, operation,
                requestId == null ? null : ModelCallObservation.safeRequestId(requestId));
        return new ModelCallPage(view(period), scope, tenant, result.getRecords(), result.getTotal(), result.getSize(),
                result.getCurrent(), result.getPages(), outcomes);
    }

    /** 全局读取只授予管理员；租户模式必须显式指定租户，避免静默忽略过滤条件。 */
    private UUID tenant(String scope, UUID value) {
        if ("TENANT".equals(scope) && value == null) throw new InvalidOptimizationRequestException("TENANT 范围必须提供 tenantId");
        if ("GLOBAL".equals(scope) && value != null) throw new InvalidOptimizationRequestException("GLOBAL 范围不能同时提供 tenantId");
        return value;
    }

    /** 服务层也验证代码，防止内部调用绕过协议校验。 */
    private String code(String value, String fallback, Set<String> allowed, String name) {
        String normalized = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new InvalidOptimizationRequestException(name + " 不属于允许的查询代码");
        return normalized;
    }

    /** 限制排行和分页上界，禁止无界扫描与负数分页。 */
    private int bounded(Integer value, int fallback, int maximum, String name) {
        int normalized = value == null ? fallback : value;
        if (normalized < 1 || normalized > maximum) throw new InvalidOptimizationRequestException(name + " 必须在 1 到 " + maximum + " 之间");
        return normalized;
    }

    /** 把排他结束日还原为管理员界面包含的日期。 */
    private PeriodView view(AnalyticsPeriod period) {
        return new PeriodView(period.fromDate(), period.toDateExclusive().minusDays(1), period.fromInclusive(),
                period.toExclusive(), period.zoneIdText());
    }

    /** 数据源未启用时报告真实失败，不返回伪造的零消耗。 */
    private ModelCallAnalyticsMapper requireMapper() {
        if (mapper == null) throw new IllegalStateException("模型对账数据库当前不可用");
        return mapper;
    }
}
