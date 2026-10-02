package com.promptoptimizer.analytics.mapper;

import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.MonthlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.UserRank;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.MetricKind;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.analytics.support.AnalyticsAcceptanceMapper;
import com.promptoptimizer.analytics.support.AnalyticsAggregationFixtureMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 PostgreSQL 验证预聚合优化仍保持账号去重、事件次数、日历补零和关键词交集口径。
 * 随机夹具在同一事务内回滚；不执行迁移、改动已有账号或调用外部模型。
 * 本类直接验证内部查询，不替代管理员接口的真实权限验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "app.security.bootstrap-admin.enabled=false",
        "app.security.bootstrap-user.password=",
        "spring.session.store-type=none",
        "app.security.login-guard.require-redis=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Transactional
class AdminAnalyticsAggregationRegressionTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_DAY = LocalDate.of(2020, 1, 30);
    private static final String JOURNAL_DIRECTORY = Path.of("target", "analytics-aggregation-journal", UUID.randomUUID().toString())
            .toAbsolutePath().normalize().toString();
    private final String seed = "aggregate-" + UUID.randomUUID().toString().replace("-", "");
    private final List<Account> accounts = new ArrayList<>();

    @Autowired private AdminAnalyticsMapper analytics;
    @Autowired private AuditEventMapper events;
    @Autowired private IdentityProvisioningMapper provisioning;
    @Autowired private AnalyticsAggregationFixtureMapper fixtures;
    @Autowired private AnalyticsAcceptanceMapper acceptance;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AdminAnalyticsService service;
    // 启动初始化早于测试事务，替换初始化器防止测试之外写入已有账号。
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    /** 使用独立 journal 目录，启动扫描不能重放开发服务已有的待投递事件。 */
    @DynamicPropertySource
    static void configureJournal(DynamicPropertyRegistry registry) {
        registry.add("app.analytics.delivery.journal-directory", () -> JOURNAL_DIRECTORY);
    }

    @Test
    void duplicateEventsCrossTenantAccountsZeroDayAndCalendarBoundariesKeepExactMetrics() {
        Scenario scenario = seedScenario();
        AnalyticsPeriod period = period(FIRST_DAY, FIRST_DAY.plusDays(4), SHANGHAI);
        AnalyticsAccountFilter filter = matchingAccounts();

        assertThat(analytics.accountCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.AccountCounts(4, 2));
        assertThat(analytics.usageCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.UsageCounts(6, 2, 3, 2));
        assertThat(analytics.dailyMetrics(period, filter)).containsExactly(
                new DailyMetric(FIRST_DAY, 5, 2, 2, 2, 1),
                new DailyMetric(FIRST_DAY.plusDays(1), 1, 1, 2, 1, 0),
                new DailyMetric(FIRST_DAY.plusDays(2), 0, 0, 2, 1, 1),
                new DailyMetric(FIRST_DAY.plusDays(3), 0, 0, 0, 0, 0));
        assertHours(period, filter, Map.of(11, 4L, 14, 2L));
        assertThat(analytics.monthlyUsage(period, period.toDateExclusive().minusDays(1), filter))
                .containsExactly(new MonthlyMetric("2020-01", 5), new MonthlyMetric("2020-02", 1));
        assertThat(analytics.deviceDistribution(period, filter)).containsExactlyInAnyOrder(
                new DeviceMetric("DESKTOP", 3, 2), new DeviceMetric("MOBILE", 3, 2), new DeviceMetric("UNKNOWN", 1, 1));
        assertThat(analytics.usageRanking(period, filter, 20)).containsExactly(
                new UserRank(scenario.beta().id(), scenario.beta().displayName(), 3, 3, 2),
                new UserRank(scenario.alpha().id(), scenario.alpha().displayName(), 3, 2, 3));
        assertThat(analytics.countOperationLogs(period.fromInclusive(), period.toExclusive(), filter, null)).isEqualTo(20);
        assertFusedMatchesIndependent(period, filter);

        var dashboard = service.dashboard(new DashboardQuery("CUSTOM", FIRST_DAY.toString(),
                FIRST_DAY.plusDays(3).toString(), null, seed, null));
        assertThat(dashboard.registeredAccountCount()).isEqualTo(4);
        assertThat(dashboard.newAccountCount()).isEqualTo(2);
        assertThat(dashboard.actualUserCount()).isEqualTo(2);
        assertThat(dashboard.activeUserCount()).isEqualTo(3);
        assertThat(dashboard.uniqueVisitorCount()).isEqualTo(2);
        // 日活总和为 6，含完整零日的分母为 4；期间去重活跃 3 不能直接除以日数。
        assertThat(dashboard.averageDailyActiveUsers()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(dashboard.rechargeStatisticsAvailable()).isFalse();
    }

    @Test
    void anonymousEventsDoNotChangeAnyGlobalAccountUsageBucketOrRanking() {
        Scenario scenario = seedScenario();
        AnalyticsPeriod period = period(FIRST_DAY, FIRST_DAY.plusDays(4), SHANGHAI);
        AnalyticsAccountFilter all = AnalyticsAccountFilter.forUser(null);
        Snapshot before = snapshot(period, all);
        var fusedBefore = analytics.dashboardMetrics(period, all);
        // 不靠邮箱筛选排除匿名：直接比较全平台条件，夹具与现有数据的合并指标必须保持不变。
        for (AnalyticsEventType type : List.of(AnalyticsEventType.APP_VISIT, AnalyticsEventType.LOGIN, AnalyticsEventType.OPTIMIZATION_SUBMITTED)) {
            assertThat(events.insert(UUID.randomUUID(), scenario.alpha().tenantId(), null, type,
                    Map.of("deviceType", "DESKTOP"), at(FIRST_DAY, 11))).isEqualTo(1);
        }
        // 全平台排行可能包含已有账号；失败信息只输出布尔值，不能把其显示名称写进测试控制台。
        assertThat(snapshot(period, all).equals(before)).as("匿名事件不得改变全平台聚合").isTrue();
        assertThat(analytics.dashboardMetrics(period, all).equals(fusedBefore))
                .as("匿名事件不得改变融合聚合").isTrue();
        assertFusedMatchesIndependent(period, all);
    }

    @Test
    void multipleEmailIdentitiesNeverMultiplyEventsAndKeywordConditionsRemainIntersections() {
        Scenario scenario = seedScenario();
        AnalyticsPeriod period = period(FIRST_DAY, FIRST_DAY.plusDays(4), SHANGHAI);
        AnalyticsAccountFilter alias = new AnalyticsAccountFilter(null, seed + "-alpha-alias", null);
        assertThat(analytics.accountCounts(period, alias)).isEqualTo(new AdminAnalyticsMapper.AccountCounts(1, 1));
        assertThat(analytics.usageCounts(period, alias)).isEqualTo(new AdminAnalyticsMapper.UsageCounts(3, 1, 1, 1));
        assertThat(analytics.dailyMetrics(period, alias)).containsExactly(
                new DailyMetric(FIRST_DAY, 3, 1, 1, 1, 1),
                new DailyMetric(FIRST_DAY.plusDays(1), 0, 0, 1, 0, 0),
                new DailyMetric(FIRST_DAY.plusDays(2), 0, 0, 1, 1, 0),
                new DailyMetric(FIRST_DAY.plusDays(3), 0, 0, 0, 0, 0));
        assertHours(period, alias, Map.of(11, 3L));
        assertThat(analytics.monthlyUsage(period, period.toDateExclusive().minusDays(1), alias))
                .containsExactly(new MonthlyMetric("2020-01", 2), new MonthlyMetric("2020-02", 1));
        assertThat(analytics.deviceDistribution(period, alias)).containsExactly(new DeviceMetric("DESKTOP", 2, 1));
        assertThat(analytics.usageRanking(period, alias, 20))
                .containsExactly(new UserRank(scenario.alpha().id(), scenario.alpha().displayName(), 3, 2, 3));
        assertFusedMatchesIndependent(period, alias);
        for (AnalyticsAccountFilter empty : List.of(
                new AnalyticsAccountFilter(null, seed + "-alpha-revoked", null),
                new AnalyticsAccountFilter(null, seed + "-alpha-contact-only", null),
                new AnalyticsAccountFilter(null, seed + "-alpha-alias", seed + "-beta"),
                new AnalyticsAccountFilter(scenario.beta().id(), seed + "-alpha-alias", null),
                AnalyticsAccountFilter.forUser(UUID.randomUUID()))) {
            assertEmpty(period, empty);
            assertFusedMatchesIndependent(period, empty);
        }
        assertThat(analytics.usageCounts(period, new AnalyticsAccountFilter(null,
                (seed + "-alpha-alias").toUpperCase(java.util.Locale.ROOT), scenario.alpha().displayName())))
                .isEqualTo(new AdminAnalyticsMapper.UsageCounts(3, 1, 1, 1));
        assertFusedMatchesIndependent(period, new AnalyticsAccountFilter(null,
                (seed + "-alpha-alias").toUpperCase(java.util.Locale.ROOT), scenario.alpha().displayName()));
    }

    @Test
    void repeatedDstHourGroupsIntoOneLocalHourAndOneUserDay() {
        UUID tenant = tenant();
        LocalDate day = LocalDate.of(2020, 11, 1);
        Account user = account(tenant, "dst", day.minusDays(1), false);
        AnalyticsPeriod period = period(day, day.plusDays(1), ZoneId.of("America/New_York"));
        event(user, AnalyticsEventType.OPTIMIZATION_SUBMITTED, OffsetDateTime.parse("2020-11-01T01:30:00-04:00"), Map.of());
        event(user, AnalyticsEventType.RESULT_EXPORTED, OffsetDateTime.parse("2020-11-01T01:30:00-05:00"), Map.of());
        AnalyticsAccountFilter filter = AnalyticsAccountFilter.forUser(user.id());
        assertThat(period.toExclusive().toInstant().getEpochSecond() - period.fromInclusive().toInstant().getEpochSecond())
                .isEqualTo(25 * 3600);
        assertThat(analytics.usageCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.UsageCounts(0, 0, 1, 1));
        assertThat(analytics.dailyMetrics(period, filter)).containsExactly(new DailyMetric(day, 0, 0, 1, 1, 0));
        assertHours(period, filter, Map.of(1, 2L));
        assertThat(analytics.monthlyUsage(period, day, filter)).containsExactly(new MonthlyMetric("2020-11", 2));
        assertThat(analytics.usageRanking(period, filter, 20))
                .containsExactly(new UserRank(user.id(), user.displayName(), 2, 0, 1));
        assertFusedMatchesIndependent(period, filter);
    }

    /** 测试事务结束后确认本次随机账号、租户和审计事件无残留，不物理清理业务数据。 */
    @AfterTransaction
    void verifiesRollbackLeftNoFixtureRows() {
        for (Account account : accounts) {
            assertThat(acceptance.remainingRows(account.id(), account.tenantId(), UUID.randomUUID()))
                    .as("本次随机夹具必须全部回滚").isZero();
        }
    }

    /** 每个复杂夹具都把融合查询的四类内部行与既有独立 Mapper 全等对照，不以同一 SQL 自证口径。 */
    private void assertFusedMatchesIndependent(AnalyticsPeriod period, AnalyticsAccountFilter filter) {
        var rows = analytics.dashboardMetrics(period, filter);
        assertThat(rows).allSatisfy(row -> assertThat(row.kind()).isNotNull());
        assertThat(rows).filteredOn(row -> row.kind() == MetricKind.SUMMARY).singleElement().satisfies(row -> {
            assertThat(row.bucket()).isNull();
            assertThat(new AdminAnalyticsMapper.UsageCounts(row.accessCount(), row.uniqueVisitors(), row.activeUsers(), row.actualUsers()))
                    .isEqualTo(analytics.usageCounts(period, filter));
        });
        var days = rows.stream().filter(row -> row.kind() == MetricKind.DAILY)
                .map(row -> new DailyMetric(LocalDate.parse(row.bucket()), row.accessCount(), row.uniqueVisitors(),
                        row.activeUsers(), row.actualUsers(), row.newAccounts()))
                .sorted(java.util.Comparator.comparing(DailyMetric::date)).toList();
        assertThat(days).isEqualTo(analytics.dailyMetrics(period, filter));
        var hours = rows.stream().filter(row -> row.kind() == MetricKind.HOURLY)
                .map(row -> new HourlyMetric(Integer.parseInt(row.bucket()), row.operationCount()))
                .sorted(java.util.Comparator.comparingInt(HourlyMetric::hour)).toList();
        assertThat(hours).isEqualTo(analytics.hourlyUsage(period, filter));
        // 同登录次数的设备顺序由 PostgreSQL collation 决定，保持 SQL 子序列，不用 Java 字典序覆盖。
        var devices = rows.stream().filter(row -> row.kind() == MetricKind.DEVICE)
                .map(row -> new DeviceMetric(row.bucket(), row.loginCount(), row.uniqueUsers())).toList();
        assertThat(devices).isEqualTo(analytics.deviceDistribution(period, filter));
    }

    /** 两个租户、重复邮箱身份和不同活跃方式共同覆盖预聚合最易误改的去重粒度。 */
    private Scenario seedScenario() {
        UUID firstTenant = tenant();
        UUID secondTenant = tenant();
        Account alpha = account(firstTenant, "alpha", FIRST_DAY, true);
        Account beta = account(secondTenant, "beta", FIRST_DAY.minusDays(1), false);
        account(firstTenant, "unused", FIRST_DAY.plusDays(2), false);
        Account loginOnly = account(secondTenant, "login-only", FIRST_DAY.minusDays(1), false);
        identity(alpha, seed + "-alpha-alias@example.test", "ACTIVE");
        identity(alpha, seed + "-alpha-revoked@example.test", "REVOKED");
        for (int index = 0; index < 3; index++) event(alpha, AnalyticsEventType.APP_VISIT, at(FIRST_DAY, 10), Map.of());
        for (int index = 0; index < 2; index++) event(alpha, AnalyticsEventType.LOGIN, at(FIRST_DAY, 9), Map.of("deviceType", "DESKTOP"));
        for (int index = 0; index < 2; index++) event(alpha, AnalyticsEventType.OPTIMIZATION_SUBMITTED, at(FIRST_DAY, 11), Map.of());
        event(alpha, AnalyticsEventType.LOGOUT, at(FIRST_DAY.plusDays(1), 10), Map.of());
        event(alpha, AnalyticsEventType.RESULT_EXPORTED, at(FIRST_DAY.plusDays(2), 11), Map.of());
        for (int index = 0; index < 2; index++) event(beta, AnalyticsEventType.APP_VISIT, at(FIRST_DAY, 10), Map.of());
        event(beta, AnalyticsEventType.LOGIN, at(FIRST_DAY, 9), Map.of("deviceType", "MOBILE"));
        event(beta, AnalyticsEventType.CONTEXT_ANALYZED, at(FIRST_DAY, 11), Map.of());
        event(beta, AnalyticsEventType.APP_VISIT, at(FIRST_DAY.plusDays(1), 14), Map.of());
        event(beta, AnalyticsEventType.LOGIN, at(FIRST_DAY.plusDays(1), 12), Map.of());
        event(beta, AnalyticsEventType.LOGIN, at(FIRST_DAY.plusDays(1), 12), Map.of("deviceType", "DESKTOP"));
        event(beta, AnalyticsEventType.PLAN_CREATED, at(FIRST_DAY.plusDays(1), 14), Map.of());
        event(beta, AnalyticsEventType.CONTEXT_PREPARED, at(FIRST_DAY.plusDays(1), 14), Map.of());
        for (int index = 0; index < 2; index++) event(loginOnly, AnalyticsEventType.LOGIN, at(FIRST_DAY.plusDays(2), 9), Map.of("deviceType", "MOBILE"));
        // 边界外事件不能被自然月骨架或预聚合顺序误带入当前区间。
        event(alpha, AnalyticsEventType.OPTIMIZATION_SUBMITTED, FIRST_DAY.atStartOfDay(SHANGHAI).toOffsetDateTime().minusNanos(1000), Map.of());
        event(alpha, AnalyticsEventType.OPTIMIZATION_SUBMITTED, FIRST_DAY.plusDays(4).atStartOfDay(SHANGHAI).toOffsetDateTime(), Map.of());
        return new Scenario(alpha, beta);
    }

    /** 创建随机租户；所有关联数据都归属测试事务。 */
    private UUID tenant() {
        UUID id = UUID.randomUUID();
        assertThat(provisioning.insertTenant(id, seed)).isEqualTo(1);
        return id;
    }

    /** 新建账号仅保存运行时随机密码的 BCrypt 哈希，既有账号不参与夹具。 */
    private Account account(UUID tenantId, String label, LocalDate createdDay, boolean admin) {
        UUID id = UUID.randomUUID();
        String displayName = seed + "-" + label;
        assertThat(provisioning.insertUserAccount(id, tenantId, seed + "-" + label + "-contact-only@example.test",
                displayName, encoder.encode(UUID.randomUUID().toString()), admin ? "PLATFORM_ADMIN" : "USER")).isEqualTo(1);
        var account = new Account(id, tenantId, displayName);
        accounts.add(account);
        assertThat(fixtures.setCreatedAt(id, tenantId, displayName, at(createdDay, 8))).isEqualTo(1);
        identity(account, seed + "-" + label + "-primary@example.test", "ACTIVE");
        return account;
    }

    /** 用不同有效身份验证 EXISTS 过滤不会放大一个账号的事件；撤销身份不能参与邮箱筛选。 */
    private void identity(Account account, String email, String status) {
        assertThat(provisioning.insertUserIdentity(UUID.randomUUID(), account.id(), "EMAIL", "local", email, email,
                status, at(FIRST_DAY.minusDays(1), 8))).isEqualTo(1);
    }

    /** 每次真实操作生成独立主键；重复次数应按事件计数而非因同一时刻被合并。 */
    private void event(Account account, AnalyticsEventType type, OffsetDateTime time, Map<String, Object> details) {
        assertThat(events.insert(UUID.randomUUID(), account.tenantId(), account.id(), type, details, time)).isEqualTo(1);
    }

    /** 按自然日创建左闭右开区间，覆盖跨月与夏令时，无固定 24 小时假设。 */
    private AnalyticsPeriod period(LocalDate from, LocalDate exclusive, ZoneId zone) {
        return new AnalyticsPeriod(from, exclusive, zone, from.atStartOfDay(zone).toOffsetDateTime(),
                exclusive.atStartOfDay(zone).toOffsetDateTime());
    }

    /** 测试 IP/账号之外无需真实时间，事件全部按明确的上海统计时区生成。 */
    private OffsetDateTime at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(SHANGHAI).toOffsetDateTime();
    }

    /** 随机关键词隔离本次四个账号，多个邮箱身份仍只计一个账号。 */
    private AnalyticsAccountFilter matchingAccounts() {
        return new AnalyticsAccountFilter(null, seed, null);
    }

    /** 核对完整 24 小时骨架，未列出的小时必须补零。 */
    private void assertHours(AnalyticsPeriod period, AnalyticsAccountFilter filter, Map<Integer, Long> counts) {
        assertThat(analytics.hourlyUsage(period, filter)).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 24).mapToObj(hour -> new HourlyMetric(hour, counts.getOrDefault(hour, 0L))).toList());
    }

    /** 无匹配账号时每类聚合都返回准确零值；不得让 bool_or 的 null 漏入数字响应。 */
    private void assertEmpty(AnalyticsPeriod period, AnalyticsAccountFilter filter) {
        assertThat(analytics.accountCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.AccountCounts(0, 0));
        assertThat(analytics.usageCounts(period, filter)).isEqualTo(new AdminAnalyticsMapper.UsageCounts(0, 0, 0, 0));
        assertThat(analytics.dailyMetrics(period, filter)).containsExactlyElementsOf(
                FIRST_DAY.datesUntil(FIRST_DAY.plusDays(4)).map(day -> new DailyMetric(day, 0, 0, 0, 0, 0)).toList());
        assertHours(period, filter, Map.of());
        assertThat(analytics.monthlyUsage(period, period.toDateExclusive().minusDays(1), filter))
                .containsExactly(new MonthlyMetric("2020-01", 0), new MonthlyMetric("2020-02", 0));
        assertThat(analytics.deviceDistribution(period, filter)).isEmpty();
        assertThat(analytics.usageRanking(period, filter, 20)).isEmpty();
        assertThat(analytics.countOperationLogs(period.fromInclusive(), period.toExclusive(), filter, null)).isZero();
    }

    /** 全平台快照只用于比较匿名插入前后，不输出已有账号或地理明细。 */
    private Snapshot snapshot(AnalyticsPeriod period, AnalyticsAccountFilter filter) {
        return new Snapshot(analytics.accountCounts(period, filter), analytics.usageCounts(period, filter),
                analytics.dailyMetrics(period, filter), analytics.hourlyUsage(period, filter),
                analytics.monthlyUsage(period, period.toDateExclusive().minusDays(1), filter),
                analytics.deviceDistribution(period, filter), analytics.usageRanking(period, filter, 100),
                analytics.countOperationLogs(period.fromInclusive(), period.toExclusive(), filter, null));
    }

    private record Account(UUID id, UUID tenantId, String displayName) { }
    private record Scenario(Account alpha, Account beta) { }
    private record Snapshot(AdminAnalyticsMapper.AccountCounts accountCounts, AdminAnalyticsMapper.UsageCounts usageCounts,
                            List<DailyMetric> dailyMetrics, List<HourlyMetric> hourlyMetrics, List<MonthlyMetric> monthlyMetrics,
                            List<DeviceMetric> devices, List<UserRank> ranking, long logCount) { }
}
