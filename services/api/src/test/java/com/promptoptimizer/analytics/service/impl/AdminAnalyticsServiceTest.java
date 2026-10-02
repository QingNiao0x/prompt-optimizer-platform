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
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.DashboardMetricRow;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.MetricKind;
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
import java.util.ArrayList;
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
        when(repository.dashboardMetrics(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(dashboardRows(
                new AdminAnalyticsMapper.UsageCounts(5, 2, 2, 1), List.of(
                new DailyMetric(LocalDate.parse("2026-09-01"), 2, 1, 2, 1, 1),
                new DailyMetric(LocalDate.parse("2026-09-02"), 0, 0, 0, 0, 0),
                new DailyMetric(LocalDate.parse("2026-09-03"), 3, 1, 1, 0, 1)
        ), List.of(new HourlyMetric(9, 3)), List.of(new DeviceMetric("MOBILE", 2, 1))));
        when(repository.monthlyUsage(any(), any(), eq(AnalyticsAccountFilter.forUser(USER_ID)))).thenReturn(List.of(new MonthlyMetric("2026-09", 4)));
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
        assertThat(view.hourlyUsage()).hasSize(24).filteredOn(metric -> metric.hour() == 9)
                .containsExactly(new HourlyMetric(9, 3));
        assertThat(view.deviceDistribution()).containsExactly(new DeviceMetric("MOBILE", 2, 1));
        assertThat(view.rechargeStatisticsAvailable()).isFalse();
        assertThat(view.rechargeByDay()).isEmpty();
        verify(recharge, never()).paidByDay(any(), any());
        verify(repository, never()).usageCounts(any(), any());
        verify(repository, never()).dailyMetrics(any(), any());
        verify(repository, never()).hourlyUsage(any(), any());
        verify(repository, never()).deviceDistribution(any(), any());
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
        when(repository.dashboardMetrics(period, AnalyticsAccountFilter.forUser(USER_ID))).thenReturn(dashboardRows(
                new AdminAnalyticsMapper.UsageCounts(1, 1, 1, 0), List.of(
                new DailyMetric(period.fromDate(), 1, 1, 1, 0, 0),
                new DailyMetric(period.fromDate().plusDays(1), 0, 0, 0, 0, 0),
                new DailyMetric(period.fromDate().plusDays(2), 0, 0, 0, 0, 0)), List.of(), List.of()));
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
        assertThat(pageCaptor.getValue().countId()).isEqualTo("countOperationLogs");
    }

    @Test
    void forwardsNormalizedAccountKeywordsToEveryDashboardSourceIncludingRecharge() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        RechargeRecordMapper recharge = mock(RechargeRecordMapper.class);
        AnalyticsPeriod period = resolver().resolve("TODAY", null, null);
        AnalyticsAccountFilter account = new AnalyticsAccountFilter(USER_ID, "demo@example.test", "演示名称");
        when(repository.accountCounts(period, account)).thenReturn(new AdminAnalyticsMapper.AccountCounts(1, 0));
        when(repository.dashboardMetrics(period, account)).thenReturn(dashboardRows(
                new AdminAnalyticsMapper.UsageCounts(1, 1, 1, 1),
                List.of(new DailyMetric(period.fromDate(), 1, 1, 1, 1, 0)), List.of(), List.of()));
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository), provider(recharge), true);
        service.dashboard(new DashboardQuery("TODAY", null, null, USER_ID, " DEMO@EXAMPLE.TEST ", " 演示名称 "));
        verify(repository).dashboardMetrics(period, account);
        verify(repository).accountCounts(period, account);
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

    @Test
    void rejectsIncompleteFusedResultsInsteadOfReturningInventedZeros() {
        AdminAnalyticsMapper repository = mock(AdminAnalyticsMapper.class);
        RechargeRecordMapper recharge = mock(RechargeRecordMapper.class);
        AnalyticsPeriod period = resolver().resolve("TODAY", null, null);
        when(repository.accountCounts(period, AnalyticsAccountFilter.forUser(USER_ID)))
                .thenReturn(new AdminAnalyticsMapper.AccountCounts(1, 0));
        when(repository.dashboardMetrics(period, AnalyticsAccountFilter.forUser(USER_ID)))
                .thenReturn(List.of(new DashboardMetricRow(MetricKind.SUMMARY, null, 0, 0, 0, 0, 0, 0, 0, 0)));
        AdminAnalyticsService service = new AdminAnalyticsServiceImpl(resolver(), provider(repository), provider(recharge), false);
        assertThatThrownBy(() -> service.dashboard(new DashboardQuery("TODAY", null, null, USER_ID, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("桶不完整");
        verify(repository, never()).monthlyUsage(any(), any(), any());
        verifyNoInteractions(recharge);
    }

    /** 使用完整小时骨架构造内部融合行，防止旧测试的部分图表序列掩盖缺桶错误。 */
    private List<DashboardMetricRow> dashboardRows(AdminAnalyticsMapper.UsageCounts summary, List<DailyMetric> days,
                                                  List<HourlyMetric> hours, List<DeviceMetric> devices) {
        List<DashboardMetricRow> rows = new ArrayList<>();
        rows.add(new DashboardMetricRow(MetricKind.SUMMARY, null, summary.accessCount(), summary.uniqueVisitors(),
                summary.activeUsers(), summary.actualUsers(), 0, 0, 0, 0));
        for (DailyMetric day : days) {
            rows.add(new DashboardMetricRow(MetricKind.DAILY, day.date().toString(), day.accessCount(), day.uniqueVisitors(),
                    day.activeUsers(), day.actualUsers(), day.newAccounts(), 0, 0, 0));
        }
        for (int hour = 0; hour < 24; hour++) {
            int bucket = hour;
            long count = hours.stream().filter(metric -> metric.hour() == bucket).mapToLong(HourlyMetric::operationCount).sum();
            rows.add(new DashboardMetricRow(MetricKind.HOURLY, Integer.toString(hour), 0, 0, 0, 0, 0, count, 0, 0));
        }
        for (DeviceMetric device : devices) {
            rows.add(new DashboardMetricRow(MetricKind.DEVICE, device.deviceType(), 0, 0, 0, 0, 0, 0,
                    device.loginCount(), device.uniqueUsers()));
        }
        return List.copyOf(rows);
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
