package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.DashboardMetricRow;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.MetricKind;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.UsageCounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证融合查询组装保留去重、零桶及排序语义，并显式拒绝不完整或越界的内部结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DashboardMetricsAssemblerTest {

    private static final LocalDate FIRST_DAY = LocalDate.of(2024, 1, 1);
    private static final AnalyticsPeriod ONE_DAY = period(FIRST_DAY, FIRST_DAY.plusDays(1));

    @Test
    void assemblesUnorderedRowsAndKeepsAccountDeduplicationSeparateFromDailySums() {
        AnalyticsPeriod period = period(FIRST_DAY, FIRST_DAY.plusDays(2));
        var rows = emptyRows(period);
        rows.set(0, new DashboardMetricRow(MetricKind.SUMMARY, null, 5, 1, 2, 1, 0, 0, 0, 0));
        rows.set(1, new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.toString(), 3, 1, 2, 1, 1, 0, 0, 0));
        rows.set(2, new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.plusDays(1).toString(), 2, 1, 1, 0, 0, 0, 0, 0));
        rows.set(3 + 9, new DashboardMetricRow(MetricKind.HOURLY, "9", 0, 0, 0, 0, 0, 3, 0, 0));
        rows.add(new DashboardMetricRow(MetricKind.DEVICE, "DESKTOP", 0, 0, 0, 0, 0, 0, 2, 1));
        rows.add(new DashboardMetricRow(MetricKind.DEVICE, "UNKNOWN", 0, 0, 0, 0, 0, 0, 3, 1));
        rows.add(new DashboardMetricRow(MetricKind.DEVICE, "MOBILE", 0, 0, 0, 0, 0, 0, 3, 2));
        Collections.reverse(rows);

        var metrics = DashboardMetricsAssembler.assemble(period, rows);
        assertThat(metrics.usageCounts()).isEqualTo(new UsageCounts(5, 1, 2, 1));
        assertThat(metrics.dailyMetrics()).containsExactly(new DailyMetric(FIRST_DAY, 3, 1, 2, 1, 1),
                new DailyMetric(FIRST_DAY.plusDays(1), 2, 1, 1, 0, 0));
        assertThat(metrics.hourlyUsage()).hasSize(24).extracting(HourlyMetric::hour)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 24).boxed().toList());
        assertThat(metrics.hourlyUsage()).filteredOn(metric -> metric.hour() == 9)
                .containsExactly(new HourlyMetric(9, 3));
        assertThat(metrics.deviceDistribution()).containsExactly(new DeviceMetric("MOBILE", 3, 2),
                new DeviceMetric("UNKNOWN", 3, 1), new DeviceMetric("DESKTOP", 2, 1));
        assertThatThrownBy(() -> metrics.dailyMetrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metrics.hourlyUsage().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metrics.deviceDistribution().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyEventsStillRequireCompleteZeroDaysAndHoursButAllowNoDevices() {
        var metrics = DashboardMetricsAssembler.assemble(ONE_DAY, emptyRows(ONE_DAY));
        assertThat(metrics.usageCounts()).isEqualTo(new UsageCounts(0, 0, 0, 0));
        assertThat(metrics.dailyMetrics()).containsExactly(new DailyMetric(FIRST_DAY, 0, 0, 0, 0, 0));
        assertThat(metrics.hourlyUsage()).hasSize(24).allSatisfy(hour -> assertThat(hour.operationCount()).isZero());
        assertThat(metrics.deviceDistribution()).isEmpty();
    }

    @Test
    void acceptsEveryDayOfALeapYearWithoutUsingFixedHourDurations() {
        AnalyticsPeriod leapYear = period(FIRST_DAY, FIRST_DAY.plusYears(1));
        var metrics = DashboardMetricsAssembler.assemble(leapYear, emptyRows(leapYear));
        assertThat(metrics.dailyMetrics()).hasSize(366);
        assertThat(metrics.dailyMetrics().getLast().date()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    void preservesDatabaseOrderAndOriginalLegacyDeviceCodesForEqualLoginCounts() {
        var rows = emptyRows(ONE_DAY);
        rows.set(0, new DashboardMetricRow(MetricKind.SUMMARY, null, 0, 0, 1, 0, 0, 0, 0, 0));
        rows.set(1, new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.toString(), 0, 0, 1, 0, 0, 0, 0, 0));
        rows.add(new DashboardMetricRow(MetricKind.DEVICE, "旧设备", 0, 0, 0, 0, 0, 0, 1, 1));
        rows.add(new DashboardMetricRow(MetricKind.DEVICE, "Z", 0, 0, 0, 0, 0, 0, 1, 1));
        assertThat(DashboardMetricsAssembler.assemble(ONE_DAY, rows).deviceDistribution())
                .containsExactly(new DeviceMetric("旧设备", 1, 1), new DeviceMetric("Z", 1, 1));
    }

    @Test
    void rejectsNullPeriodAndAccessSumOverflow() {
        assertThatThrownBy(() -> DashboardMetricsAssembler.assemble(null, emptyRows(ONE_DAY)))
                .isInstanceOf(IllegalStateException.class);
        AnalyticsPeriod period = period(FIRST_DAY, FIRST_DAY.plusDays(2));
        var rows = emptyRows(period);
        rows.set(0, new DashboardMetricRow(MetricKind.SUMMARY, null, Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0));
        rows.set(1, new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.toString(), Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0));
        rows.set(2, new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.plusDays(1).toString(), 1, 0, 0, 0, 0, 0, 0, 0));
        assertThatThrownBy(() -> DashboardMetricsAssembler.assemble(period, rows))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("溢出");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResults")
    void rejectsMalformedResultsInsteadOfFillingInventedZeros(String scenario, List<DashboardMetricRow> rows) {
        assertThatThrownBy(() -> DashboardMetricsAssembler.assemble(ONE_DAY, rows)).as(scenario)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("聚合");
    }

    /** 覆盖缺行、重复、类型/桶错误、全部计数字段、UNION 列漂移及汇总矛盾。 */
    private static Stream<Arguments> invalidResults() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("null result", null));
        cases.add(Arguments.of("empty result", List.of()));
        for (int index : List.of(0, 1, 2)) {
            var missing = emptyRows(ONE_DAY);
            missing.remove(index);
            cases.add(Arguments.of("missing row " + index, missing));
            var duplicate = emptyRows(ONE_DAY);
            duplicate.add(duplicate.get(index));
            cases.add(Arguments.of("duplicate row " + index, duplicate));
        }
        var nullRow = emptyRows(ONE_DAY);
        nullRow.add(null);
        cases.add(Arguments.of("null row", nullRow));
        cases.add(Arguments.of("null kind", replaced(0, row(null, null))));
        cases.add(Arguments.of("summary bucket", replaced(0, row(MetricKind.SUMMARY, "unexpected"))));
        for (String bucket : new String[]{null, "not-a-date", "2024-1-1", "2023-12-31", "2024-01-02"}) {
            cases.add(Arguments.of("invalid day " + bucket, replaced(1, row(MetricKind.DAILY, bucket))));
        }
        for (String bucket : new String[]{null, "not-an-hour", "-1", "24", "00"}) {
            cases.add(Arguments.of("invalid hour " + bucket, replaced(2, row(MetricKind.HOURLY, bucket))));
        }
        cases.add(Arguments.of("null device", appended(row(MetricKind.DEVICE, null))));
        var duplicateDevice = appended(row(MetricKind.DEVICE, "UNKNOWN"));
        duplicateDevice.add(row(MetricKind.DEVICE, "UNKNOWN"));
        cases.add(Arguments.of("duplicate device", duplicateDevice));
        for (int column = 0; column < 8; column++) {
            long[] counts = new long[8];
            counts[column] = -1;
            cases.add(Arguments.of("negative count " + column, replaced(0, new DashboardMetricRow(MetricKind.SUMMARY, null,
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7]))));
        }
        cases.add(Arguments.of("unused summary column", replaced(0,
                new DashboardMetricRow(MetricKind.SUMMARY, null, 0, 0, 0, 0, 1, 0, 0, 0))));
        cases.add(Arguments.of("unused day column", replaced(1,
                new DashboardMetricRow(MetricKind.DAILY, FIRST_DAY.toString(), 0, 0, 0, 0, 0, 1, 0, 0))));
        cases.add(Arguments.of("unused hour column", replaced(2,
                new DashboardMetricRow(MetricKind.HOURLY, "0", 0, 0, 1, 0, 0, 0, 0, 0))));
        cases.add(Arguments.of("unused device column", appended(
                new DashboardMetricRow(MetricKind.DEVICE, "UNKNOWN", 0, 0, 0, 0, 0, 1, 0, 0))));
        cases.add(Arguments.of("visitors exceed active", replaced(0,
                new DashboardMetricRow(MetricKind.SUMMARY, null, 1, 1, 0, 0, 0, 0, 0, 0))));
        cases.add(Arguments.of("actual exceed active", replaced(0,
                new DashboardMetricRow(MetricKind.SUMMARY, null, 0, 0, 0, 1, 0, 0, 0, 0))));
        cases.add(Arguments.of("visitors exceed visits", replaced(0,
                new DashboardMetricRow(MetricKind.SUMMARY, null, 0, 1, 1, 0, 0, 0, 0, 0))));
        cases.add(Arguments.of("device users exceed logins", appended(
                new DashboardMetricRow(MetricKind.DEVICE, "UNKNOWN", 0, 0, 0, 0, 0, 0, 1, 2))));
        var deviceOrder = appended(new DashboardMetricRow(MetricKind.DEVICE, "DESKTOP", 0, 0, 0, 0, 0, 0, 1, 1));
        deviceOrder.add(new DashboardMetricRow(MetricKind.DEVICE, "MOBILE", 0, 0, 0, 0, 0, 0, 2, 1));
        cases.add(Arguments.of("device login counts not descending", deviceOrder));
        cases.add(Arguments.of("summary daily access mismatch", replaced(0,
                new DashboardMetricRow(MetricKind.SUMMARY, null, 1, 0, 0, 0, 0, 0, 0, 0))));
        return cases.stream();
    }

    /** 规范零值结果：汇总一行、每日一行和全部 24 小时，没有事件时不创建设备行。 */
    private static ArrayList<DashboardMetricRow> emptyRows(AnalyticsPeriod period) {
        var rows = new ArrayList<DashboardMetricRow>();
        rows.add(row(MetricKind.SUMMARY, null));
        period.fromDate().datesUntil(period.toDateExclusive()).forEach(date -> rows.add(row(MetricKind.DAILY, date.toString())));
        for (int hour = 0; hour < 24; hour++) rows.add(row(MetricKind.HOURLY, Integer.toString(hour)));
        return rows;
    }

    /** 创建一行指定类型的零值结果供边界夹具使用。 */
    private static DashboardMetricRow row(MetricKind kind, String bucket) {
        return new DashboardMetricRow(kind, bucket, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 替换规范结果的单行，保持其他桶完整以定位目标错误。 */
    private static List<DashboardMetricRow> replaced(int index, DashboardMetricRow row) {
        var rows = emptyRows(ONE_DAY);
        rows.set(index, row);
        return rows;
    }

    /** 附加异常行，不复用可变夹具以免参数化用例互相污染。 */
    private static ArrayList<DashboardMetricRow> appended(DashboardMetricRow row) {
        var rows = emptyRows(ONE_DAY);
        rows.add(row);
        return rows;
    }

    /** 按当地午夜构造测试区间，与正式解析的自然日边界一致。 */
    private static AnalyticsPeriod period(LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        return new AnalyticsPeriod(from, to, zone, from.atStartOfDay(zone).toOffsetDateTime(),
                to.atStartOfDay(zone).toOffsetDateTime());
    }
}
