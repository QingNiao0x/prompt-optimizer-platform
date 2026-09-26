package com.promptoptimizer.analytics.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.mapper.OptimizationSessionMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import com.promptoptimizer.provider.mapper.PlatformModelMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
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
        parameters.put("userId", UUID.fromString("11111111-1111-4111-8111-111111111111"));

        BoundSql sql = configuration.getMappedStatement(AdminAnalyticsMapper.class.getName() + ".dailyMetrics")
                .getBoundSql(parameters);

        assertThat(sql.getSql()).contains("generate_series", "CAST(? AS timestamp without time zone)",
                "AT TIME ZONE CAST(? AS text)").doesNotContain("Asia/Shanghai");
        assertThat(sql.getParameterMappings()).isNotEmpty();
        assertThat(sql.getParameterMappings()).anySatisfy(mapping ->
                assertThat(mapping.getProperty()).isEqualTo("period.zoneId.id"));
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

        assertThat(sql).contains("jsonb_build_object", "left(btrim(replace", "LIMIT", "OFFSET");
        assertThat(sql).doesNotContain("optimized_prompt", "context_snapshot", "permission_policy");
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
