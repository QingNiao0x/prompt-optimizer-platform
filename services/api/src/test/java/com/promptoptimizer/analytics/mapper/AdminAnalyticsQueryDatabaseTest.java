package com.promptoptimizer.analytics.mapper;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog;
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
import java.util.stream.Stream;

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

        assertThat(analyticsMapper.dailyMetrics(period, AnalyticsAccountFilter.forUser(userId)))
                .containsExactly(new DailyMetric(day, 1, 1, 1, 1, 1, 0, 0));
        assertThat(analyticsMapper.monthlyUsage(period, day, AnalyticsAccountFilter.forUser(userId)))
                .containsExactly(new MonthlyMetric("2026-09", 1));
        assertThat(analyticsMapper.hourlyUsage(period, AnalyticsAccountFilter.forUser(userId)))
                .extracting(HourlyMetric::hour)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23);
        assertThat(analyticsMapper.hourlyUsage(period, AnalyticsAccountFilter.forUser(userId)))
                .filteredOn(metric -> metric.hour() == 11)
                .containsExactly(new HourlyMetric(11, 1));
        assertThat(analyticsMapper.deviceDistribution(period, AnalyticsAccountFilter.forUser(userId)))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.deviceType()).isEqualTo("DESKTOP");
                    assertThat(metric.loginCount()).isEqualTo(1);
                    assertThat(metric.uniqueUsers()).isEqualTo(1);
                });
        assertThat(analyticsMapper.usageRanking(period, AnalyticsAccountFilter.forUser(userId), 20))
                .singleElement()
                .satisfies(rank -> {
                    assertThat(rank.userId()).isEqualTo(userId);
                    assertThat(rank.operationCount()).isEqualTo(1);
                    assertThat(rank.loginCount()).isEqualTo(1);
                    assertThat(rank.activeDays()).isEqualTo(1);
                });
        AdminAnalyticsMapper.AccountCounts accounts = analyticsMapper.accountCounts(period, AnalyticsAccountFilter.forUser(userId));
        assertThat(accounts.registeredAccounts()).isEqualTo(1);
        assertThat(accounts.newAccounts()).isEqualTo(1);
        AdminAnalyticsMapper.UsageCounts usage = analyticsMapper.usageCounts(period, AnalyticsAccountFilter.forUser(userId));
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
        assertThat(analyticsMapper.usageRanking(threeDays, AnalyticsAccountFilter.forUser(userId), 20)).singleElement().satisfies(rank -> {
            assertThat(rank.activeDays()).isEqualTo(3);
            assertThat(rank.operationCount()).isEqualTo(1);
            assertThat(rank.loginCount()).isEqualTo(1);
        });
        assertThat(analyticsMapper.dailyMetrics(threeDays, AnalyticsAccountFilter.forUser(userId)))
                .extracting(DailyMetric::activeUsers).containsExactly(1L, 1L, 1L);
    }

    @Test
    void accountKeywordsAreLiteralCombinedAndDoNotMultiplyEventsForMultipleEmailIdentities() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String tag = UUID.randomUUID().toString();
        LocalDate day = LocalDate.of(2026, 9, 25);
        AnalyticsPeriod period = new AnalyticsPeriod(day, day.plusDays(1), ZONE,
                day.atStartOfDay(ZONE).toOffsetDateTime(), day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", firstTenant, "keyword-test-one");
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", secondTenant, "keyword-test-two");
        seedAccount(jdbc, firstTenant, first, "分析小组 Alpha_100%", day);
        seedAccount(jdbc, secondTenant, second, "分析小组 Beta", day);
        seedAccount(jdbc, secondTenant, revoked, "分析小组 Alpha_100%", day);
        seedEmail(jdbc, first, tag + "+one@analytics.test", "ACTIVE");
        seedEmail(jdbc, first, tag + "+two@analytics.test", "ACTIVE");
        seedEmail(jdbc, second, tag + "+other@analytics.test", "ACTIVE");
        seedEmail(jdbc, revoked, tag + "+revoked@analytics.test", "REVOKED");
        insertEvent(jdbc, firstTenant, first, "APP_VISIT", "{}", at(day, 9));
        insertEvent(jdbc, firstTenant, first, "APP_VISIT", "{}", at(day, 10));
        insertEvent(jdbc, firstTenant, first, "OPTIMIZATION_SUBMITTED", "{}", at(day, 11));
        insertEvent(jdbc, firstTenant, first, "CONTEXT_ANALYZED", "{}", at(day, 12));
        insertEvent(jdbc, firstTenant, first, "LOGIN", "{\"deviceType\":\"DESKTOP\"}", at(day, 13));
        insertEvent(jdbc, secondTenant, second, "OPTIMIZATION_SUBMITTED", "{}", at(day, 11));
        insertEvent(jdbc, secondTenant, revoked, "OPTIMIZATION_SUBMITTED", "{}", at(day, 11));

        // 大小写与空白归一化；名字包含 %/_ 时仍只匹配字面字符，不扩大到 Beta 账号。
        AnalyticsAccountFilter filter = new AnalyticsAccountFilter(null, " " + tag.toUpperCase() + "+ ", " ALPHA_100% ");
        assertThat(analyticsMapper.accountCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.AccountCounts(1, 1));
        assertThat(analyticsMapper.usageCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.UsageCounts(2, 1, 1, 1));
        assertThat(analyticsMapper.dailyMetrics(period, filter)).containsExactly(new DailyMetric(day, 2, 1, 1, 1, 1, 0, 0));
        assertThat(analyticsMapper.hourlyUsage(period, filter)).filteredOn(item -> item.hour() == 11)
                .containsExactly(new HourlyMetric(11, 1));
        assertThat(analyticsMapper.monthlyUsage(period, day, filter)).containsExactly(new MonthlyMetric("2026-09", 2));
        assertThat(analyticsMapper.deviceDistribution(period, filter)).singleElement().satisfies(item -> {
            assertThat(item.loginCount()).isEqualTo(1);
            assertThat(item.uniqueUsers()).isEqualTo(1);
        });
        assertThat(analyticsMapper.usageRanking(period, filter, 20)).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(first);
            assertThat(item.operationCount()).isEqualTo(2);
            assertThat(item.activeDays()).isEqualTo(1);
        });
        Page<OperationLog> page = new Page<>(1, 2);
        page.setCountId("countOperationLogs");
        var operations = analyticsMapper.selectOperationLogs(page, period.fromInclusive(), period.toExclusive(), filter, null);
        assertThat(operations.getTotal()).isEqualTo(5);
        assertThat(operations.getRecords()).hasSize(2).allSatisfy(item -> assertThat(item.userId()).isEqualTo(first));
        Page<OperationLog> next = new Page<>(2, 2);
        next.setCountId("countOperationLogs");
        var nextOperations = analyticsMapper.selectOperationLogs(next, period.fromInclusive(), period.toExclusive(), filter, null);
        assertThat(nextOperations.getTotal()).isEqualTo(5);
        assertThat(nextOperations.getRecords())
                .hasSize(2).allSatisfy(item -> assertThat(item.userId()).isEqualTo(first));

        // 显示名称改为分页后的标量投影，不能改变顺序、跨页唯一性或安全字段的空值语义。
        var returnedLogs = Stream.concat(operations.getRecords().stream(), nextOperations.getRecords().stream()).toList();
        assertThat(returnedLogs).extracting(OperationLog::eventId).doesNotHaveDuplicates();
        assertThat(returnedLogs).extracting(item -> item.occurredAt().toInstant())
                .containsExactly(at(day, 13).toInstant(), at(day, 12).toInstant(),
                        at(day, 11).toInstant(), at(day, 10).toInstant());
        assertThat(returnedLogs).extracting(OperationLog::eventType)
                .containsExactly("LOGIN", "CONTEXT_ANALYZED", "OPTIMIZATION_SUBMITTED", "APP_VISIT");
        assertThat(returnedLogs).allSatisfy(item -> {
            assertThat(item.userId()).isEqualTo(first);
            assertThat(item.displayName()).isEqualTo("分析小组 Alpha_100%");
            assertThat(item.clientIp()).isNull();
            assertThat(item.country()).isNull();
            assertThat(item.province()).isNull();
            assertThat(item.city()).isNull();
            assertThat(item.loginCountry()).isNull();
            assertThat(item.loginProvince()).isNull();
            assertThat(item.loginCity()).isNull();
            assertThat(item.deviceType()).isEqualTo("LOGIN".equals(item.eventType()) ? "DESKTOP" : "UNKNOWN");
        });
        assertThat(analyticsMapper.countOperationLogs(period.fromInclusive(), period.toExclusive(), filter, "LOGIN"))
                .isEqualTo(1);
        Page<OperationLog> logins = new Page<>(1, 2);
        logins.setCountId("countOperationLogs");
        var loginOperations = analyticsMapper.selectOperationLogs(logins, period.fromInclusive(), period.toExclusive(), filter, "LOGIN");
        assertThat(loginOperations.getTotal()).isEqualTo(1);
        assertThat(loginOperations.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(first);
            assertThat(item.eventType()).isEqualTo("LOGIN");
            assertThat(item.displayName()).isEqualTo("分析小组 Alpha_100%");
            assertThat(item.deviceType()).isEqualTo("DESKTOP");
        });

        // ID、邮箱和名称按交集生效，冲突条件不能回退为更宽的查询。
        AnalyticsAccountFilter conflict = new AnalyticsAccountFilter(second, tag + "+", "alpha_100%");
        assertThat(analyticsMapper.accountCounts(period, conflict).registeredAccounts()).isZero();
        assertThat(analyticsMapper.usageRanking(period, conflict, 20)).isEmpty();
        assertThat(analyticsMapper.dailyMetrics(period, conflict)).containsExactly(new DailyMetric(day, 0, 0, 0, 0, 0, 0, 0));
        assertThat(analyticsMapper.usageCounts(period, new AnalyticsAccountFilter(null, "' OR 1=1 --", null)).accessCount()).isZero();
        assertThat(analyticsMapper.accountCounts(period, new AnalyticsAccountFilter(null, tag + "+revoked", null)).registeredAccounts()).isZero();

        // 未指定邮箱时撤销的身份不删除历史账号；平台管理员可查看两个租户中符合名称的账号。
        assertThat(analyticsMapper.accountCounts(period, new AnalyticsAccountFilter(null, null, "分析小组 Alpha_100%"))
                .registeredAccounts()).isEqualTo(2);
        assertThat(analyticsMapper.usageCounts(period, new AnalyticsAccountFilter(first, "   ", "   ")))
                .isEqualTo(new AdminAnalyticsMapper.UsageCounts(2, 1, 1, 1));
    }

    private static void seedAccount(JdbcTemplate jdbc, UUID tenant, UUID user, String displayName, LocalDate day) {
        jdbc.update("INSERT INTO user_account (id, tenant_id, email, display_name, status, created_at) VALUES (?, ?, NULL, ?, 'ACTIVE', ?)",
                user, tenant, displayName, at(day, 1));
    }

    private static void seedEmail(JdbcTemplate jdbc, UUID user, String email, String status) {
        jdbc.update("INSERT INTO user_identity (id, user_id, identity_type, issuer, identifier, normalized_identifier, status) VALUES (?, ?, 'EMAIL', 'local', ?, ?, ?)",
                UUID.randomUUID(), user, email, email, status);
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
