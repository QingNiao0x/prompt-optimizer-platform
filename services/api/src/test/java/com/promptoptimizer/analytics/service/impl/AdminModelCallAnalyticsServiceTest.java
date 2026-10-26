package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.dto.ModelCallQuery;
import com.promptoptimizer.analytics.dto.TokenRankingQuery;
import com.promptoptimizer.analytics.mapper.ModelCallAnalyticsMapper;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证匿名排行范围、日历边界和内部调用的输入校验。 */
class AdminModelCallAnalyticsServiceTest {
    private final ModelCallAnalyticsMapper mapper = mock(ModelCallAnalyticsMapper.class);
    private final AnalyticsPeriodResolver periods = new AnalyticsPeriodResolver(ZoneId.of("Asia/Shanghai"), Clock.systemUTC());

    @Test
    void requestOutcomesUseTheSameTenantDateOperationAndCorrelationAsThePagedRows() {
        UUID tenant = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallView>(2, 10);
        page.setTotal(11);
        var outcomes = new com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallOutcomes(3, 1, 1, 2, 1, 0, 0);
        when(mapper.selectModelCalls(any(), any(), eq(tenant), eq("prompt.optimize"), eq(requestId))).thenReturn(page);
        when(mapper.requestOutcomes(any(), eq(tenant), eq("prompt.optimize"), eq(requestId))).thenReturn(outcomes);
        var result = service(periods).modelCalls(new ModelCallQuery("2026-10-10", "2026-10-10", "TENANT", tenant,
                "prompt.optimize", requestId, 2, 10));
        assertThat(result.outcomes()).isEqualTo(outcomes);
        assertThat(result.current()).isEqualTo(2);
        verify(mapper).requestOutcomes(argThat(period -> period.fromDate().equals(LocalDate.of(2026, 10, 10))),
                eq(tenant), eq("prompt.optimize"), eq(requestId));
    }

    private AdminModelCallAnalyticsServiceImpl service(AnalyticsPeriodResolver resolver) {
        @SuppressWarnings("unchecked") ObjectProvider<ModelCallAnalyticsMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return new AdminModelCallAnalyticsServiceImpl(resolver, provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.analyze", "image.plan", "image.generate"})
    void multimodalOperationsRemainQueryableWithTheSameScopedFilters(String operation) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallView>(1, 10);
        when(mapper.selectModelCalls(any(), any(), isNull(), eq(operation), isNull())).thenReturn(page);
        service(periods).modelCalls(new ModelCallQuery("2026-10-10", "2026-10-10", "GLOBAL", null, operation, null, 1, 10));
        verify(mapper).requestOutcomes(any(), isNull(), eq(operation), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DAY", "WEEK", "MONTH"})
    void wholeCalendarPeriodAndEmptyDataAreReturned(String period) {
        when(mapper.tokenRanking(any(), isNull(), eq("OPERATION"), eq(20))).thenReturn(List.of());
        var result = service(periods).tokenRanking(new TokenRankingQuery(period, "2026-10-10", null, null, null, null));
        assertThat(result.ranking()).isEmpty();
        assertThat(result.period().zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(result.period().fromInclusive().toInstant()).isEqualTo(result.period().fromDate()
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        assertThat(result.period().toExclusive().toInstant()).isEqualTo(result.period().toDateInclusive()
                .plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    @Test
    void tenantAndSourceArePassedAsTypedParameters() {
        UUID tenant = UUID.randomUUID();
        service(periods).tokenRanking(new TokenRankingQuery("MONTH", "2026-10-10", "TENANT", tenant, "SOURCE", 100));
        verify(mapper).tokenRanking(any(), eq(tenant), eq("SOURCE"), eq(100));
    }

    @Test
    void scopeLimitsDatesAndFiltersAreValidatedBeforeDatabaseAccess() {
        var service = service(periods);
        for (var query : List.of(
                new TokenRankingQuery("DAY", "2026-10-10", "TENANT", null, null, 20),
                new TokenRankingQuery("DAY", "2026-10-10", "GLOBAL", UUID.randomUUID(), null, 20),
                new TokenRankingQuery("DAY", "2026-02-30", null, null, null, 20),
                new TokenRankingQuery("YEAR", "2026-10-10", null, null, null, 20),
                new TokenRankingQuery("DAY", "2026-10-10", null, null, "unsafe", 20),
                new TokenRankingQuery("DAY", "2026-10-10", null, null, null, 0))) {
            assertThatThrownBy(() -> service.tokenRanking(query)).isInstanceOf(InvalidOptimizationRequestException.class);
        }
        assertThatThrownBy(() -> service.modelCalls(new ModelCallQuery("2026-10-11", "2026-10-10", null, null, null, null, 1, 20)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.modelCalls(new ModelCallQuery("2026-10-10", "2026-10-10", null, null, "unsafe", null, 1, 20)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void daylightSavingDayUsesActualLocalMidnights() {
        var resolver = new AnalyticsPeriodResolver(ZoneId.of("America/New_York"), Clock.systemUTC());
        var view = service(resolver).tokenRanking(new TokenRankingQuery("DAY", "2026-03-08", null, null, null, null)).period();
        assertThat(Duration.between(view.fromInclusive(), view.toExclusive())).isEqualTo(Duration.ofHours(23));
    }
}
