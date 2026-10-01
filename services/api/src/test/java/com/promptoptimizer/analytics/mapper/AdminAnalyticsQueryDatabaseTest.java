package com.promptoptimizer.analytics.mapper;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.MonthlyMetric;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 PostgreSQL 确认仪表盘按本地日分组，且重复出现的时区参数不会触发 42803。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "spring.session.store-type=none",
        "app.security.login-guard.require-redis=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Transactional
class AdminAnalyticsQueryDatabaseTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private AdminAnalyticsMapper analyticsMapper;

    @Autowired
    private DataSource dataSource;

    // ApplicationRunner 先于测试事务运行，替换初始化器以免写入本地已有账号。
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    @Test
    void dailyAndMonthlyBucketsGroupByLocalCalendar() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 25);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 25, 1, 0, 0, 0, ZoneOffset.ofHours(8));
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, "analytics-test");
        jdbc.update(
                """
                INSERT INTO user_account (id, tenant_id, email, display_name, status, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                userId,
                tenantId,
                userId + "@analytics.test",
                "统计用户",
                createdAt
        );
        insertEvent(jdbc, tenantId, userId, "APP_VISIT", "{}", at(day, 10));
        insertEvent(jdbc, tenantId, userId, "OPTIMIZATION_SUBMITTED", "{}", at(day, 11));
        insertEvent(jdbc, tenantId, userId, "LOGIN", "{\"deviceType\":\"DESKTOP\"}", at(day, 12));
        AnalyticsPeriod period = new AnalyticsPeriod(
                day,
                day.plusDays(1),
                ZONE,
                day.atStartOfDay(ZONE).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()
        );

        assertThat(analyticsMapper.dailyMetrics(period, userId))
                .containsExactly(new DailyMetric(day, 1, 1, 1, 1, 1));
        assertThat(analyticsMapper.monthlyUsage(period, day, userId))
                .containsExactly(new MonthlyMetric("2026-09", 1));
        assertThat(analyticsMapper.hourlyUsage(period, userId))
                .extracting(HourlyMetric::hour)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23);
        assertThat(analyticsMapper.hourlyUsage(period, userId))
                .filteredOn(metric -> metric.hour() == 11)
                .containsExactly(new HourlyMetric(11, 1));
        assertThat(analyticsMapper.deviceDistribution(period, userId))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.deviceType()).isEqualTo("DESKTOP");
                    assertThat(metric.loginCount()).isEqualTo(1);
                    assertThat(metric.uniqueUsers()).isEqualTo(1);
                });
        assertThat(analyticsMapper.usageRanking(period, userId, 20))
                .singleElement()
                .satisfies(rank -> {
                    assertThat(rank.userId()).isEqualTo(userId);
                    assertThat(rank.operationCount()).isEqualTo(1);
                    assertThat(rank.loginCount()).isEqualTo(1);
                    assertThat(rank.activeDays()).isEqualTo(1);
                });
        AdminAnalyticsMapper.AccountCounts accounts = analyticsMapper.accountCounts(period, userId);
        assertThat(accounts.registeredAccounts()).isEqualTo(1);
        assertThat(accounts.newAccounts()).isEqualTo(1);
        AdminAnalyticsMapper.UsageCounts usage = analyticsMapper.usageCounts(period, userId);
        assertThat(usage.accessCount()).isEqualTo(1);
        assertThat(usage.uniqueVisitors()).isEqualTo(1);
        assertThat(usage.activeUsers()).isEqualTo(1);
        assertThat(usage.actualUsers()).isEqualTo(1);

        // 仅访问和仅退出的日期也属于活跃日，关键操作数与登录次数保持各自口径。
        insertEvent(jdbc, tenantId, userId, "APP_VISIT", "{}", at(day.plusDays(1), 10));
        insertEvent(jdbc, tenantId, userId, "APP_VISIT", "{}", at(day.plusDays(1), 11));
        insertEvent(jdbc, tenantId, userId, "LOGOUT", "{}", at(day.plusDays(2), 12));
        AnalyticsPeriod threeDays = new AnalyticsPeriod(day, day.plusDays(3), ZONE,
                day.atStartOfDay(ZONE).toOffsetDateTime(), day.plusDays(3).atStartOfDay(ZONE).toOffsetDateTime());
        assertThat(analyticsMapper.usageRanking(threeDays, userId, 20)).singleElement().satisfies(rank -> {
            assertThat(rank.activeDays()).isEqualTo(3);
            assertThat(rank.operationCount()).isEqualTo(1);
            assertThat(rank.loginCount()).isEqualTo(1);
        });
        assertThat(analyticsMapper.dailyMetrics(threeDays, userId))
                .extracting(DailyMetric::activeUsers).containsExactly(1L, 1L, 1L);
    }

    private static OffsetDateTime at(LocalDate day, int hour) {
        return OffsetDateTime.of(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), hour, 0, 0, 0,
                ZoneOffset.ofHours(8));
    }

    private static void insertEvent(
            JdbcTemplate jdbc,
            UUID tenantId,
            UUID userId,
            String eventType,
            String details,
            OffsetDateTime occurredAt
    ) {
        jdbc.update(
                """
                INSERT INTO audit_event (id, tenant_id, actor_user_id, event_type, details, occurred_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                UUID.randomUUID(),
                tenantId,
                userId,
                eventType,
                details,
                occurredAt
        );
    }
}
