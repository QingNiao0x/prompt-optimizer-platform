package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.MonthlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog;
import com.promptoptimizer.analytics.dto.AnalyticsViews.UserRank;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.dto.OperationLogQuery;
import com.promptoptimizer.analytics.dto.UsageRankingQuery;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证仪表盘平均日活包含零活跃日，并确保未启用支付记录时不查询虚构数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AdminAnalyticsServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void averagesDailyActiveUsersAcrossEveryCalendarDayAndKeepsAdminCounts() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        RechargeRecordMapper recharge = mock(RechargeRecordMapper.class);
        AnalyticsPeriodResolver resolver = resolver();
        AnalyticsPeriod period = resolver.resolve("CUSTOM", "2026-09-01", "2026-09-03");
        when(repository.accountCounts(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(new AdminAnalyticsMapper.AccountCounts(7, 2));
        when(repository.usageCounts(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(new AdminAnalyticsMapper.UsageCounts(5, 2, 2, 1));
        when(repository.dailyMetrics(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(List.of(
                new DailyMetric(LocalDate.parse("2026-09-01"), 2, 1, 2, 1, 1),
                new DailyMetric(LocalDate.parse("2026-09-02"), 0, 0, 0, 0, 0),
                new DailyMetric(LocalDate.parse("2026-09-03"), 3, 1, 1, 0, 1)
        ));
        when(repository.hourlyUsage(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(List.of(new HourlyMetric(9, 3)));
        when(repository.monthlyUsage(any(), any(), eq(AnalyticsAccountFilter.forUser(USER_ID)))).thenReturn(List.of(new MonthlyMetric("2026-09", 4)));
        when(repository.deviceDistribution(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(List.of(new DeviceMetric("MOBILE", 2, 1)));
        when(repository.usageRanking(period, AnalyticsAccountFilter.forUser(USER_ID), 10)).thenReturn(List.of(
                new UserRank(USER_ID, "Admin", 4, 1, 2)
        ));
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver, provider(repository), provider(recharge), false);

        var view = service.dashboard(new DashboardQuery("CUSTOM", "2026-09-01", "2026-09-03", USER_ID, null, null));

        assertThat(view.registeredAccountCount()).isEqualTo(7);
        assertThat(view.newAccountCount()).isEqualTo(2);
        assertThat(view.activeUserCount()).isEqualTo(2);
        assertThat(view.actualUserCount()).isEqualTo(1);
        assertThat(view.averageDailyActiveUsers()).isEqualByComparingTo("1.00");
        assertThat(view.dailyMetrics()).hasSize(3);
        assertThat(view.rechargeStatisticsAvailable()).isFalse();
        assertThat(view.rechargeByDay()).isEmpty();
        verify(recharge, never()).paidByDay(any(), any());
    }

    @Test
    void rejectsInvalidRangeBeforeRunningQueries() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository),
                provider(mock(RechargeRecordMapper.class)), false);

        assertThatThrownBy(() -> service.dashboard(new DashboardQuery("CUSTOM", "2026-09-04", "2026-09-03", null, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        verify(repository, never()).accountCounts(any(), any());
    }

    @Test
    void invalidDateSpanAndRankingOrLogParametersNeverReachTheMapper() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository),
                provider(mock(RechargeRecordMapper.class)), false);
        assertThatThrownBy(() -> service.dashboard(new DashboardQuery("CUSTOM", "2024-01-01", "2025-01-01", null, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.usageRanking(new UsageRankingQuery("DAY", "+999999999-12-31", null, 20, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.usageRanking(new UsageRankingQuery("DAY", "2026-10-01", null, 101, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.operationLogs(new OperationLogQuery("2026-10-01", "2026-10-01", null, "INVALID", 1, 10, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.operationLogs(new OperationLogQuery("2026-10-01", "2026-10-01", null, null, 0, 10, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.operationLogs(new OperationLogQuery("2026-10-01", "2026-10-01", null, null, 1, 101, null, null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void roundsAverageToTwoPlacesAndPreservesFixedRecentMonthWindow() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        AnalyticsPeriod period = resolver().resolve("CUSTOM", "2024-01-01", "2024-01-03");
        when(repository.accountCounts(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(new AdminAnalyticsMapper.AccountCounts(1, 0));
        when(repository.usageCounts(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(new AdminAnalyticsMapper.UsageCounts(1, 1, 1, 0));
        when(repository.dailyMetrics(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(List.of(
                new DailyMetric(period.fromDate(), 1, 1, 1, 0, 0),
                new DailyMetric(period.fromDate().plusDays(1), 0, 0, 0, 0, 0),
                new DailyMetric(period.fromDate().plusDays(2), 0, 0, 0, 0, 0)));
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository),
                provider(mock(RechargeRecordMapper.class)), false);
        assertThat(service.dashboard(new DashboardQuery("CUSTOM", "2024-01-01", "2024-01-03", USER_ID, null, null))
                .averageDailyActiveUsers()).isEqualByComparingTo("0.33");
        ArgumentCaptor<AnalyticsPeriod> months = ArgumentCaptor.forClass(AnalyticsPeriod.class);
        verify(repository).monthlyUsage(months.capture(), eq(LocalDate.of(2026, 9, 25)), eq(AnalyticsAccountFilter.forUser(USER_ID)));
        assertThat(months.getValue().fromDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(months.getValue().toDateExclusive()).isEqualTo(LocalDate.of(2026, 9, 26));
    }

    @Test
    void paginatesOperationLogsThroughMybatisPlusPageParameter() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        Page<OperationLog> mapperPage = new Page<>(2, 10);
        mapperPage.setTotal(15);
        mapperPage.setRecords(List.of(new OperationLog(
                UUID.randomUUID(), USER_ID, "Admin", "LOGIN",
                java.time.OffsetDateTime.parse("2026-09-25T10:00:00+08:00"),
                null, null, null, null, null, null, null, "DESKTOP")));
        when(repository.selectOperationLogs(any(), any(), any(), eq(AnalyticsAccountFilter.forUser(USER_ID)), eq("LOGIN")))
                .thenReturn(mapperPage);
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository),
                provider(mock(RechargeRecordMapper.class)), false);

        var result = service.operationLogs(new OperationLogQuery(
                "2026-09-24", "2026-09-26", USER_ID, "login", 2, 10, null, null));

        assertThat(result.current()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(15);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.records()).hasSize(1);
        ArgumentCaptor<Page<OperationLog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(repository).selectOperationLogs(pageCaptor.capture(), any(), any(), eq(AnalyticsAccountFilter.forUser(USER_ID)), eq("LOGIN"));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        assertThat(pageCaptor.getValue().optimizeCountSql()).isFalse();
    }

    @Test
    void forwardsNormalizedAccountKeywordsToEveryDashboardSourceIncludingRecharge() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        RechargeRecordMapper recharge = mock(RechargeRecordMapper.class);
        AnalyticsPeriod period = resolver().resolve("TODAY", null, null);
        AnalyticsAccountFilter account = new AnalyticsAccountFilter(USER_ID, "demo@example.test", "演示名称");
        when(repository.accountCounts(period, account)).thenReturn(new AdminAnalyticsMapper.AccountCounts(1, 0));
        when(repository.usageCounts(period, account)).thenReturn(new AdminAnalyticsMapper.UsageCounts(1, 1, 1, 1));
        when(repository.dailyMetrics(period, account)).thenReturn(List.of(new DailyMetric(period.fromDate(), 1, 1, 1, 1, 0)));
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository), provider(recharge), true);
        service.dashboard(new DashboardQuery("TODAY", null, null, USER_ID, " DEMO@EXAMPLE.TEST ", " 演示名称 "));
        verify(repository).hourlyUsage(period, account);
        verify(repository).deviceDistribution(period, account);
        verify(repository).monthlyUsage(any(), any(), eq(account));
        verify(recharge).paidByDay(period, account);
    }

    @Test
    void oversizedAccountKeywordsAreRejectedBeforeAnyDatabaseQuery() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository),
                provider(mock(RechargeRecordMapper.class)), false);
        assertThatThrownBy(() -> service.dashboard(new DashboardQuery("TODAY", null, null, null, "x".repeat(321), null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.usageRanking(new UsageRankingQuery("DAY", "2026-09-25", null, 20, null, "名".repeat(81))))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.operationLogs(new OperationLogQuery("2026-09-25", "2026-09-25", null, null, 1, 10, "x".repeat(321), null)))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        verifyNoInteractions(repository);
    }

    private AnalyticsPeriodResolver resolver() {
        return new AnalyticsPeriodResolver(ZoneId.of("Asia/Shanghai"),
                Clock.fixed(java.time.Instant.parse("2026-09-25T02:00:00Z"), ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
