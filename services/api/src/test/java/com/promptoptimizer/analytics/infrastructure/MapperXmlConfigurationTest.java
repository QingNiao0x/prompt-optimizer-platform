package com.promptoptimizer.analytics.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.mapper.OptimizationSessionMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import org.apache.ibatis.mapping.ParameterMapping;
import com.promptoptimizer.provider.mapper.PlatformModelMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证模块 Mapper XML 均可加载且 PostgreSQL 时间边界仍采用参数绑定。 */
class MapperXmlConfigurationTest {

    @Test
    void parsesAllMapperXmlResources() throws Exception {
        Configuration configuration = parseMappers();

        List<Class<?>> mapperTypes = List.of(
                AdminAnalyticsMapper.class, AuditEventMapper.class, OptimizationRecordMapper.class,
                OptimizationSessionMapper.class, IdentityProvisioningMapper.class, UserAccountMapper.class,
                UserIdentityMapper.class, RechargeRecordMapper.class, PlatformModelMapper.class);
        for (Class<?> mapperType : mapperTypes) {
            assertThat(BaseMapper.class.isAssignableFrom(mapperType))
                    .as("%s 不得继承无范围的 BaseMapper 删除和更新", mapperType.getSimpleName())
                    .isFalse();
            for (var method : mapperType.getDeclaredMethods()) {
                assertThat(configuration.hasStatement(mapperType.getName() + "." + method.getName()))
                        .as("Mapped XML statement %s.%s", mapperType.getSimpleName(), method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void mapperXmlDoesNotPhysicallyDeleteBusinessRows() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml");
        assertThat(resources).isNotEmpty();
        for (Resource resource : resources) {
            String xml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .toUpperCase(Locale.ROOT);
            assertThat(xml)
                    .as(resource.getFilename())
                    .doesNotContain("<DELETE")
                    .doesNotContain("DELETE FROM");
        }

        Configuration configuration = parseMappers();
        String passwordUpdate = configuration.getMappedStatement(
                        UserAccountMapper.class.getName() + ".updatePasswordHash")
                .getBoundSql(new HashMap<>())
                .getSql()
                .toLowerCase(Locale.ROOT);
        assertThat(passwordUpdate).contains("password_hash");
        assertThat(passwordUpdate).doesNotContain("platform_role", "tenant_id =", "status =");
    }

    @Test
    void dailyMetricsUsesExplicitTypedSeriesAndBoundZoneParameter() throws Exception {
        Configuration configuration = parseMappers();
        LocalDate from = LocalDate.parse("2026-09-25");
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        AnalyticsPeriod period = new AnalyticsPeriod(from, from.plusDays(1), zoneId,
                from.atStartOfDay(zoneId).toOffsetDateTime(),
                from.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime());
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("period", period);
        parameters.put("account", AnalyticsAccountFilter.forUser(UUID.fromString("11111111-1111-4111-8111-111111111111")));

        BoundSql sql = configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + ".dailyMetrics")
                .getBoundSql(parameters);

        assertThat(sql.getSql()).contains("generate_series", "CAST(? AS timestamp without time zone)",
                "AT TIME ZONE CAST(? AS text)").doesNotContain("Asia/Shanghai");
        assertThat(sql.getParameterMappings()).isNotEmpty();
        assertThat(sql.getParameterMappings()).anySatisfy(mapping ->
                assertThat(mapping.getProperty()).isEqualTo("period.zoneIdText"));
        assertResolvedParameters(configuration, AdminAnalyticsMapper.class.getName() + ".dailyMetrics", parameters,
                "Asia/Shanghai");
        parameters.put("toDateLast", from);
        parameters.put("limit", 20);
        for (String statement : List.of(
                AdminAnalyticsMapper.class.getName() + ".dashboardMetrics",
                AdminAnalyticsMapper.class.getName() + ".hourlyUsage",
                AdminAnalyticsMapper.class.getName() + ".monthlyUsage",
                AdminAnalyticsMapper.class.getName() + ".usageRanking",
                RechargeRecordMapper.class.getName() + ".paidByDay")) {
            assertResolvedParameters(configuration, statement, parameters, "Asia/Shanghai");
        }
    }

    @Test
    void everyAnalyticsSourceBindsAccountKeywordsIncludingDeferredRechargeSource() throws Exception {
        Configuration configuration = parseMappers();
        LocalDate from = LocalDate.of(2026, 9, 25);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var params = new HashMap<String, Object>();
        params.put("period", new AnalyticsPeriod(from, from.plusDays(1), zone,
                from.atStartOfDay(zone).toOffsetDateTime(), from.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
        params.put("account", new AnalyticsAccountFilter(null, "' OR 1=1 --", "alpha_100%"));
        params.put("fromInclusive", from.atStartOfDay(zone).toOffsetDateTime());
        params.put("toExclusive", from.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        params.put("eventType", null);
        params.put("toDateLast", from);
        params.put("limit", 20);
        for (String method : List.of("accountCounts", "usageCounts", "dashboardMetrics", "dailyMetrics", "hourlyUsage", "monthlyUsage",
                "deviceDistribution", "usageRanking", "selectOperationLogs", "countOperationLogs")) {
            assertKeywordBinding(configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + "." + method).getBoundSql(params));
        }
        assertKeywordBinding(configuration.getMappedStatement(RechargeRecordMapper.class.getName() + ".paidByDay").getBoundSql(params));
    }

    @Test
    void dashboardAggregationReusesOneBoundEventScanAndMapsExplicitMetricKinds() throws Exception {
        Configuration configuration = parseMappers();
        LocalDate day = LocalDate.of(2026, 10, 2);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var parameters = new HashMap<String, Object>();
        parameters.put("period", new AnalyticsPeriod(day, day.plusDays(1), zone,
                day.atStartOfDay(zone).toOffsetDateTime(), day.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
        parameters.put("account", AnalyticsAccountFilter.forUser(null));
        var statement = configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + ".dashboardMetrics");
        String sql = statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ");

        assertThat(sql.split("FROM audit_event e", -1).length - 1).isEqualTo(1);
        assertThat(sql).contains("local_events AS MATERIALIZED", "account_days AS MATERIALIZED", "UNION ALL",
                "generate_series", "AT TIME ZONE CAST(? AS text)", "actor_user_id IS NOT NULL")
                .doesNotContain("Asia/Shanghai", "password_hash", "${", "SELECT e.details");
        for (AdminAnalyticsMapper.MetricKind kind : AdminAnalyticsMapper.MetricKind.values()) {
            assertThat(sql).contains("'" + kind.name() + "'");
        }
        assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(AdminAnalyticsMapper.DashboardMetricRow.class);
        assertThat(statement.getResultMaps().getFirst().getConstructorResultMappings().getFirst().getJavaType())
                .isEqualTo(AdminAnalyticsMapper.MetricKind.class);
        assertResolvedParameters(configuration, statement.getId(), parameters, "Asia/Shanghai");
    }

    @Test
    void operationLogCountSharesPredicatesWithoutSortingOrDetailProjection() throws Exception {
        Configuration configuration = parseMappers();
        var params = new HashMap<String, Object>();
        params.put("fromInclusive", java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        params.put("toExclusive", java.time.OffsetDateTime.parse("2026-10-02T00:00:00Z"));
        params.put("account", new AnalyticsAccountFilter(null, "alpha@example.test", "alpha"));
        params.put("eventType", "LOGIN");
        BoundSql count = configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + ".countOperationLogs")
                .getBoundSql(params);
        BoundSql list = configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + ".selectOperationLogs")
                .getBoundSql(params);
        String countSql = count.getSql().replaceAll("\\s+", " ").trim();
        String listSql = list.getSql().replaceAll("\\s+", " ").trim();
        assertThat(countSql).startsWith("SELECT COUNT(*) FROM audit_event e")
                .doesNotContain("ORDER BY", "LEFT JOIN", "e.details");
        assertThat(countSql.substring(countSql.indexOf("WHERE e.actor_user_id")))
                .isEqualTo(listSql.substring(listSql.indexOf("WHERE e.actor_user_id"), listSql.lastIndexOf("ORDER BY")).trim());
        assertThat(count.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactlyElementsOf(list.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
    }

    private void assertKeywordBinding(BoundSql sql) {
        assertThat(sql.getSql()).contains("EXISTS", "strpos", "user_identity").doesNotContain("' OR 1=1 --", "alpha_100%", "${");
        assertThat(sql.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .contains("account.email", "account.displayName");
    }

    @Test
    void historyListSelectsSummaryColumnsOnly() throws Exception {
        Configuration configuration = parseMappers();
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", UUID.fromString("11111111-1111-4111-8111-111111111111"));
        parameters.put("workspaceId", UUID.fromString("22222222-2222-4222-8222-222222222222"));
        parameters.put("keyword", null);
        parameters.put("createdFrom", null);
        parameters.put("createdToExclusive", null);

        String sql = configuration.getMappedStatement(
                        OptimizationRecordMapper.class.getName() + ".selectPageByScope")
                .getBoundSql(parameters)
                .getSql();

        assertThat(sql).contains("jsonb_build_object", "left(btrim(replace");
        assertThat(sql).doesNotContain("optimized_prompt", "context_snapshot", "permission_policy", "LIMIT", "OFFSET");
    }

    private void assertResolvedParameters(
            Configuration configuration,
            String statementId,
            HashMap<String, Object> parameters,
            String expectedZone
    ) {
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(parameters);
        assertThat(boundSql.getSql()).doesNotContain("zoneId.id", "Asia/Shanghai");
        MetaObject parametersMeta = configuration.newMetaObject(parameters);
        assertThat(boundSql.getParameterMappings()).isNotEmpty();
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            Object value = parametersMeta.getValue(mapping.getProperty());
            if ("period.zoneIdText".equals(mapping.getProperty())) {
                assertThat(value).isEqualTo(expectedZone);
            }
        }
        assertThat(boundSql.getParameterMappings())
                .as(statementId)
                .anySatisfy(mapping -> assertThat(mapping.getProperty()).isEqualTo("period.zoneIdText"));
    }

    private Configuration parseMappers() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml");
        assertThat(resources).isNotEmpty();
        for (Resource resource : resources) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource.toString(), configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
}
