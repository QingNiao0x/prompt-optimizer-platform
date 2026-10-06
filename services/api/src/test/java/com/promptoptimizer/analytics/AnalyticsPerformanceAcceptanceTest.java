package com.promptoptimizer.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.PostgreDialect;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.dto.OperationLogQuery;
import com.promptoptimizer.analytics.support.AnalyticsPerformanceFixtureMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式启用的本地 PostgreSQL 性能验收：100,000 条明细和四个独立并发回滚事务。
 * 用户确认的本地验收目标是单查询可见十万条事件、四路并发、仪表盘 p95 不超过两秒且无错误。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "analytics.performance.enabled", matches = "true")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.session.store-type=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "app.security.login-guard.require-redis=false", "app.provider.mode=mock",
        "app.analytics.recharge.enabled=false", "decorator.datasource.enabled=false",
        "mybatis-plus.configuration.local-cache-scope=STATEMENT",
        "logging.level.com.promptoptimizer=WARN"
})
class AnalyticsPerformanceAcceptanceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsPerformanceAcceptanceTest.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final double LOCAL_P95_BUDGET_MS = 2000;
    @Autowired private AdminAnalyticsMapper analytics;
    @Autowired private AnalyticsPerformanceFixtureMapper fixture;
    @Autowired private IdentityProvisioningMapper provisioning;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private AdminAnalyticsService service;
    @Autowired private UserDetailsService userDetails;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SqlSessionFactory sqlSessions;
    @Autowired private DataSource dataSource;
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    @DynamicPropertySource
    static void isolateJournal(DynamicPropertyRegistry properties) {
        try {
            Path journal = Files.createTempDirectory("analytics-performance-journal-");
            properties.add("app.analytics.delivery.journal-directory", journal::toString);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法创建性能验收专用审计目录", failure);
        }
    }

    @Test
    void realisticAggregationAndConcurrentReadersStayWithinLocalBudget() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "local PostgreSQL; test-only random data; no migration; rollback verified");
        report.put("confirmedAcceptanceGoal", "100000 events visible per query; 4 concurrent readers; dashboard p95 <= 2000 ms; zero query errors");
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("passed", false);
        Path output = Path.of("target", "analytics-performance-acceptance.json");
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        try {
            // 只保留公开的数据库版本；不读取或输出 URL、账号和连接属性。
            try (var connection = dataSource.getConnection()) {
                report.put("databaseProductVersion", connection.getMetaData().getDatabaseProductVersion());
            }
            report.put("serial100k", measureTransaction(100_000, 1000, 15, true));
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            // 每个并发线程拥有自己的未提交数据和连接；最后均回滚，不让夹具进入其他会话的查询结果。
            try (var workers = Executors.newFixedThreadPool(4)) {
                CountDownLatch ready = new CountDownLatch(4);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Map<String, Object>>> tasks = new ArrayList<>();
                List<Map<String, Object>> concurrent = new ArrayList<>();
                long concurrentStart = System.nanoTime();
                for (int i = 0; i < 4; i++) tasks.add(workers.submit(
                        () -> measureTransaction(100_000, 1000, 8, false, ready, start)));
                try {
                    // 四个连接都完成各自的夹具和预热后才释放采样；异常或超时也必须释放其他线程。
                    assertThat(ready.await(60, TimeUnit.SECONDS)).as("四路读者须在有限时间内完成预热").isTrue();
                } finally {
                    start.countDown();
                }
                for (var result : tasks) concurrent.add(result.get());
                double concurrentElapsed = (System.nanoTime() - concurrentStart) / 1_000_000_000d;
                report.put("concurrent4x100k", concurrent);
                report.put("concurrentWallSecondsIncludingSeedAndWarmup", concurrentElapsed);
                report.put("concurrentCompletedMeasuredDashboardRequests", 4 * 8);
                report.put("concurrentSynchronizedReaders", 4);
                report.put("concurrentWarmupCompletedBeforeMeasuredStart", true);
                report.put("errorRate", 0);
                for (var measurement : concurrent) assertBudget(measurement);
            }
            assertBudget((Map<?, ?>) report.get("serial100k"));
            report.put("passed", true);
        } catch (Exception | AssertionError failure) {
            // 失败也保留已完成测量；不保存异常 message，避免诊断报告带入 SQL 参数或认证内容。
            report.put("failureType", failure.getClass().getSimpleName());
            report.put("passed", false);
            throw failure;
        } finally {
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        LOGGER.warn("event=analytics.performance.acceptance serialEvents=100000 concurrentReaders=4 "
                + "concurrentEvents=400000 rollbackVerified=true report=target/analytics-performance-acceptance.json");
    }

    /** 在单个事务中产生夹具、预热和采样，并在回滚后再次核查随机租户无残留。 */
    private Map<String, Object> measureTransaction(int events, int accounts, int samples, boolean capturePlan) {
        return measureTransaction(events, accounts, samples, capturePlan, null, null);
    }

    /** 并发采样在预热后等待统一起跑点；单读者测量不需要屏障。 */
    private Map<String, Object> measureTransaction(int events, int accounts, int samples, boolean capturePlan,
                                                 CountDownLatch ready, CountDownLatch startGate) {
        AtomicBoolean signalled = new AtomicBoolean();
        UUID tenant = UUID.randomUUID();
        String seed = UUID.randomUUID().toString();
        LocalDate last = LocalDate.now(ZONE);
        LocalDate first = last.minusDays(364);
        var period = new AnalyticsPeriod(first, last.plusDays(1), ZONE,
                first.atStartOfDay(ZONE).toOffsetDateTime(), last.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        // 全局管理员查询不加 userId/email 条件，避免仅测试索引命中的小集合而漏掉真正的聚合负载。
        var account = AnalyticsAccountFilter.forUser(null);
        var transaction = new TransactionTemplate(transactions);
        Map<String, Object> measurement;
        try {
            measurement = transaction.execute(status -> {
            status.setRollbackOnly();
            provisioning.insertTenant(tenant, "analytics-performance");
            assertThat(fixture.insertAccounts(tenant, seed, accounts, period.fromInclusive())).isEqualTo(accounts);
            assertThat(fixture.insertIdentities(seed, accounts)).isEqualTo(accounts);
            assertThat(fixture.insertEvents(tenant, seed, accounts, events, period.fromInclusive())).isEqualTo(events);
            UUID adminId = UUID.randomUUID();
            String adminEmail = seed + "-admin@analytics-performance.invalid";
            provisioning.insertUserAccount(adminId, tenant, adminEmail, "analytics-performance-admin",
                    passwordEncoder.encode(UUID.randomUUID() + "Aa!"), "PLATFORM_ADMIN");
            provisioning.insertUserIdentity(UUID.randomUUID(), adminId, "EMAIL", "local", adminEmail, adminEmail,
                    "ACTIVE", period.fromInclusive());
            UUID workspaceId = UUID.randomUUID();
            provisioning.insertWorkspace(workspaceId, tenant, "analytics-performance", "", adminId);
            provisioning.insertWorkspaceMember(workspaceId, adminId, "OWNER");
            var principal = userDetails.loadUserByUsername(adminEmail);
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
            for (int warm = 0; warm < 2; warm++) queryBundle(period, account, events, accounts);
            awaitMeasuredStart(ready, startGate, signalled);
            List<Double> durations = new ArrayList<>();
            List<Double> dashboardDurations = new ArrayList<>();
            Map<String, List<Double>> perQuery = new LinkedHashMap<>();
            for (int sample = 0; sample < samples; sample++) {
                long start = System.nanoTime();
                var view = service.dashboard(new DashboardQuery("CUSTOM", first.toString(), last.toString(), null, null, null));
                dashboardDurations.add(elapsed(start));
                assertThat(view.dailyMetrics()).hasSize(365);
                assertThat(view.activeUserCount()).isGreaterThanOrEqualTo(accounts);
                assertThat(view.directEnhancementCount()).isGreaterThanOrEqualTo(events / 10);
                assertThat(view.planCompletedCount()).isGreaterThanOrEqualTo(events / 10);
                start = System.nanoTime();
                var timings = queryBundle(period, account, events, accounts);
                timings.forEach((name, ms) -> perQuery.computeIfAbsent(name, ignored -> new ArrayList<>()).add(ms));
                durations.add((System.nanoTime() - start) / 1_000_000d);
            }
            Collections.sort(durations);
            Collections.sort(dashboardDurations);
            double p95 = dashboardDurations.get((int) Math.ceil(samples * .95) - 1);
            Map<String, Object> result = new LinkedHashMap<>(Map.<String, Object>of("events", events, "accounts", accounts, "samples", samples,
                    "bundleP50Ms", durations.get(samples / 2), "dashboardP95Ms", p95,
                    "maxMs", durations.get(samples - 1), "bundleQueryGroupsPerSample", 8,
                    "days", 365, "accountFilter", "global admin; no userId/email/name filter"));
            result.put("dashboardP50Ms", dashboardDurations.get(samples / 2));
            result.put("dashboardP95Ms", p95);
            result.put("bundleP95Ms", durations.get((int) Math.ceil(samples * .95) - 1));
            Map<String, Object> querySummaries = new LinkedHashMap<>();
            perQuery.forEach((name, values) -> {
                Collections.sort(values);
                querySummaries.put(name, Map.of("p50Ms", values.get(values.size() / 2),
                        "p95Ms", values.get((int) Math.ceil(values.size() * .95) - 1)));
            });
            result.put("perQuery", querySummaries);
            if (capturePlan) result.put("plans", capturePlans(period, account));
            return result;
            });
        } finally {
            // 查询失败或预算不达标也清理线程身份并验证回滚，不能污染后续测试或留存随机账号。
            signalReady(ready, signalled);
            SecurityContextHolder.clearContext();
            assertThat(fixture.remainingRows(tenant)).as("性能夹具必须完全回滚").isZero();
        }
        return measurement;
    }

    /** 保证每个读者只计入一次；预热失败的线程也能让主线程释放其他正在等待的读者。 */
    private static void signalReady(CountDownLatch ready, AtomicBoolean signalled) {
        if (ready != null && signalled.compareAndSet(false, true)) ready.countDown();
    }

    /** 起跑屏障有超时边界，线程中断保留原始 cause，不能永久阻塞测试。 */
    private static void awaitMeasuredStart(CountDownLatch ready, CountDownLatch startGate, AtomicBoolean signalled) {
        if (ready == null || startGate == null) return;
        signalReady(ready, signalled);
        try {
            if (!startGate.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("性能验收起跑屏障等待超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("性能验收起跑等待被中断", failure);
        }
    }

    /** 在报告已保留测量结果之后判断预算，失败时仍可核对本轮真实 p95。 */
    private static void assertBudget(Map<?, ?> measurement) {
        assertThat((Double) measurement.get("dashboardP95Ms"))
                .as("用户确认的本地仪表盘查询预算（毫秒）").isLessThanOrEqualTo(LOCAL_P95_BUDGET_MS);
    }

    /** 使用生产 Mapper 和分页拦截器，不用测试 SQL 替代被验收的聚合查询。 */
    private Map<String, Double> queryBundle(AnalyticsPeriod period, AnalyticsAccountFilter account, int events, int accounts) {
        Map<String, Double> timings = new LinkedHashMap<>();
        long start = System.nanoTime();
        assertThat(analytics.accountCounts(period, account).registeredAccounts()).isGreaterThanOrEqualTo(accounts);
        timings.put("accountCounts", elapsed(start));
        start = System.nanoTime();
        var usage = analytics.usageCounts(period, account);
        assertThat(usage.accessCount()).isGreaterThanOrEqualTo(events / 10);
        assertThat(usage.activeUsers()).isGreaterThanOrEqualTo(accounts);
        timings.put("usageCounts", elapsed(start));
        start = System.nanoTime();
        assertThat(analytics.dailyMetrics(period, account)).hasSize(365);
        timings.put("dailyMetrics", elapsed(start));
        start = System.nanoTime();
        assertThat(analytics.hourlyUsage(period, account)).hasSize(24);
        timings.put("hourlyUsage", elapsed(start));
        start = System.nanoTime();
        assertThat(analytics.monthlyUsage(period, period.toDateExclusive().minusDays(1), account)).hasSizeGreaterThanOrEqualTo(12);
        timings.put("monthlyUsage", elapsed(start));
        start = System.nanoTime();
        assertThat(analytics.deviceDistribution(period, account)).hasSizeGreaterThanOrEqualTo(3);
        timings.put("deviceDistribution", elapsed(start));
        start = System.nanoTime();
        assertThat(analytics.usageRanking(period, account, 20)).hasSize(20);
        timings.put("usageRanking", elapsed(start));
        start = System.nanoTime();
        // 从生产应用服务进入，包含当前管理员复核和专用 COUNT 语句，避免夹具遗漏真实分页路径。
        var logs = service.operationLogs(new OperationLogQuery(period.fromDate().toString(),
                period.toDateExclusive().minusDays(1).toString(), null, null, 1, 20, null, null));
        assertThat(logs.total()).isGreaterThanOrEqualTo(events);
        assertThat(logs.records()).hasSize(20);
        timings.put("operationLogsCountAndPage", elapsed(start));
        return timings;
    }

    private static double elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000d;
    }

    /** 为生产 Mapper SQL 绑定同样参数，只保留计划节点与性能字段，不保存查询条件或个人信息。 */
    private Map<String, Object> capturePlans(AnalyticsPeriod period, AnalyticsAccountFilter account) {
        Map<String, Object> plans = new LinkedHashMap<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("period", period);
        parameters.put("account", account);
        parameters.put("limit", 20);
        parameters.put("fromInclusive", period.fromInclusive());
        parameters.put("toExclusive", period.toExclusive());
        parameters.put("eventType", null);
        for (String method : List.of("dashboardMetrics", "usageCounts", "dailyMetrics", "hourlyUsage", "usageRanking",
                "countOperationLogs", "selectOperationLogs")) {
            var statement = sqlSessions.getConfiguration().getMappedStatement(AdminAnalyticsMapper.class.getName() + "." + method);
            var sql = statement.getBoundSql(parameters);
            if (method.equals("selectOperationLogs")) sql = pagePlanSql(sql, parameters);
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var prepared = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                new DefaultParameterHandler(statement, parameters, sql).setParameters(prepared);
                try (var result = prepared.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    plans.put(method, safePlan(json.readTree(result.getString(1)).get(0)));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("性能验收无法采集参数化查询计划", exception);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
        return plans;
    }

    /** 复用生产 PostgreDialect 和参数映射生成第一页 20 条计划，不能把无限制查询冒充实际分页负载。 */
    private BoundSql pagePlanSql(BoundSql original, Map<String, Object> parameters) {
        var configuration = sqlSessions.getConfiguration();
        var dialect = new PostgreDialect().buildPaginationSql(original.getSql(), 0, 20);
        var mappings = new ArrayList<>(original.getParameterMappings());
        Map<String, Object> additional = new LinkedHashMap<>(original.getAdditionalParameters());
        dialect.consumers(mappings, configuration, additional);
        var paginated = new BoundSql(configuration, dialect.getDialectSql(), mappings, parameters);
        additional.forEach(paginated::setAdditionalParameter);
        return paginated;
    }

    /** 删除 Filter/Index Cond 等可能包含参数值的字段，只留下性能判断所需的结构。 */
    private Object safePlan(com.fasterxml.jackson.databind.JsonNode node) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String field : List.of("Node Type", "Relation Name", "Index Name", "Plan Rows", "Actual Rows",
                "Actual Total Time", "Actual Loops", "Shared Hit Blocks", "Shared Read Blocks", "Temp Read Blocks",
                "Temp Written Blocks", "Planning Time", "Execution Time")) {
            if (node.has(field)) safe.put(field, json.convertValue(node.get(field), Object.class));
        }
        if (node.has("Plan")) safe.put("Plan", safePlan(node.get("Plan")));
        if (node.has("Plans")) {
            List<Object> children = new ArrayList<>();
            node.get("Plans").forEach(child -> children.add(safePlan(child)));
            safe.put("Plans", children);
        }
        return safe;
    }
}
