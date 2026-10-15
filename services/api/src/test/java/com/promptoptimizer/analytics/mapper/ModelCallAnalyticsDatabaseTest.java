package com.promptoptimizer.analytics.mapper;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallView;
import com.promptoptimizer.common.logging.ModelCallLogger.TokenUsage;
import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 显式启用的本机 PostgreSQL 验证；不用 Spring Boot/Flyway、不迁移表结构。
 * 仅连接 localhost/127.0.0.1，合成租户及审计明细在同一事务内插入，finally 回滚。
 */
@EnabledIfSystemProperty(named = "model.reconciliation.database-test", matches = "true")
class ModelCallAnalyticsDatabaseTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void requestOutcomesSeparateRepairsFromFailuresAndExcludeIncompleteOrCrossTenantChains() throws Exception {
        try (SqlSession session = openLocalSession()) {
            try {
                UUID tenant = UUID.randomUUID();
                UUID other = UUID.randomUUID();
                var fixtures = session.getMapper(ModelCallFixtureMapper.class);
                fixtures.insertTenant(tenant, "response-outcome-fixture");
                fixtures.insertTenant(other, "response-outcome-other-fixture");
                var writer = new FixtureWriter(session.getMapper(AuditEventMapper.class));
                var mapper = session.getMapper(ModelCallAnalyticsMapper.class);
                var range = period(LocalDate.of(2046, 10, 11), ZONE);
                var at = range.fromInclusive();
                String first = UUID.randomUUID().toString();
                String repaired = UUID.randomUUID().toString();
                String failed = UUID.randomUUID().toString();
                writer.insert(attemptFact(tenant, at, first, "prompt.optimize", 1, true, false));
                writer.insert(attemptFact(tenant, at.plusSeconds(1), first, "plan.generate", 1, true, false));
                writer.insert(attemptFact(tenant, at.plusSeconds(2), repaired, "prompt.optimize", 1, false, true));
                writer.insert(attemptFact(tenant, at.plusSeconds(3), repaired, "prompt.optimize", 2, true, false));
                for (int attempt = 1; attempt <= 3; attempt++) {
                    writer.insert(attemptFact(tenant, at.plusSeconds(3 + attempt), failed, "prompt.optimize", attempt, false, attempt < 3));
                }
                writer.insert(attemptFact(tenant, at.plusSeconds(7), UUID.randomUUID().toString(), "prompt.optimize", 2, true, false));
                writer.insert(attemptFact(tenant, at.plusSeconds(8), UUID.randomUUID().toString(), "prompt.optimize", 1, false, true));
                String duplicate = UUID.randomUUID().toString();
                writer.insert(attemptFact(tenant, at.plusSeconds(9), duplicate, "prompt.optimize", 1, true, false));
                writer.insert(attemptFact(tenant, at.plusSeconds(10), duplicate, "prompt.optimize", 1, true, false));
                String crossingDay = UUID.randomUUID().toString();
                writer.insert(attemptFact(tenant, at.minusSeconds(1), crossingDay, "prompt.optimize", 1, false, true));
                writer.insert(attemptFact(tenant, at.plusSeconds(11), crossingDay, "prompt.optimize", 2, true, false));
                writer.insert(attemptFact(tenant, at.plusSeconds(12), null, "prompt.optimize", 1, true, false));
                writer.insert(attemptFact(other, at.plusSeconds(13), repaired, "prompt.optimize", 1, true, false));
                writer.insert(fact(tenant, at.plusSeconds(14), "prompt.optimize", true, "MOCK", true, null));
                writer.insert(fact(tenant, at.plusSeconds(15), "prompt.optimize", false, "NOT_SENT", false, null));
                writer.insert(fact(tenant, at.plusSeconds(16), "context.embedding", true, "RESPONSE_RECEIVED", false, null));
                var summary = mapper.requestOutcomes(range, tenant, null, null);
                assertThat(summary.completedRequestCount()).isEqualTo(4);
                assertThat(summary.firstSuccessCount()).isEqualTo(2);
                assertThat(summary.repairedSuccessCount()).isEqualTo(1);
                assertThat(summary.repairedRequestCount()).isEqualTo(2);
                assertThat(summary.finalFailureCount()).isEqualTo(1);
                assertThat(summary.incompleteRequestCount()).isEqualTo(4);
                assertThat(summary.uncorrelatedAttemptCount()).isEqualTo(1);
                assertThat(mapper.requestOutcomes(range, null, null, null).completedRequestCount()).isEqualTo(5);
                var filtered = mapper.requestOutcomes(range, tenant, "prompt.optimize", repaired);
                assertThat(filtered.completedRequestCount()).isEqualTo(1);
                assertThat(filtered.repairedSuccessCount()).isEqualTo(1);
                assertThat(mapper.requestOutcomes(range, tenant, "context.embedding", null).completedRequestCount()).isZero();
                var rows = mapper.selectModelCalls(new Page<>(1, 20), range, tenant, "prompt.optimize", repaired).getRecords();
                assertThat(rows).filteredOn(row -> !row.successful()).singleElement().satisfies(row -> {
                    assertThat(row.validationReason()).isEqualTo("ASSESSMENT_DECISION_CONFLICT");
                    assertThat(row.validationField()).isEqualTo("assessment.gaps");
                });
                assertThat(rows).filteredOn(ModelCallView::successful).singleElement()
                        .satisfies(row -> assertThat(row.validationReason()).isNull());
                String unsafeRequest = UUID.randomUUID().toString();
                var unsafeBase = attemptFact(tenant, at.plusSeconds(17), unsafeRequest, "prompt.optimize", 1, false, false);
                var unsafeDetails = new LinkedHashMap<String, Object>(unsafeBase.details());
                unsafeDetails.put("validationReason", "private-model-message");
                unsafeDetails.put("validationField", "assessment.privateData");
                writer.insert(new PendingAuditEvent(unsafeBase.id(), tenant, null, unsafeBase.eventType(), unsafeDetails, unsafeBase.occurredAt()));
                assertThat(mapper.selectModelCalls(new Page<>(1, 20), range, tenant, null, unsafeRequest).getRecords())
                        .singleElement().satisfies(row -> {
                            assertThat(row.validationReason()).isNull();
                            assertThat(row.validationField()).isNull();
                        });
            } finally { session.rollback(); }
        }
    }

    /** 在现有匿名事实夹具上绑定重试链；所有数据仍由同一事务回滚，不创建持久业务账号。 */
    private PendingAuditEvent attemptFact(UUID tenant, OffsetDateTime at, String requestId, String operation,
                                          int attempt, boolean success, boolean willRetry) {
        var base = fact(tenant, at, operation, success, "RESPONSE_RECEIVED", false, null);
        var details = new LinkedHashMap<String, Object>(base.details());
        details.put("requestId", requestId);
        details.put("attempt", attempt);
        details.put("willRetry", willRetry);
        details.put("validationReason", success ? null : "ASSESSMENT_DECISION_CONFLICT");
        details.put("validationField", success ? null : "assessment.gaps");
        return new PendingAuditEvent(base.id(), tenant, null, base.eventType(), details, at);
    }

    @Test
    void realPostgresAggregatesAnonymousAttemptsAcrossTenantsAndPaginatesSafeProjection() throws Exception {
        try (SqlSession session = openLocalSession()) {
            try {
                UUID first = UUID.randomUUID();
                UUID second = UUID.randomUUID();
                var fixtures = session.getMapper(ModelCallFixtureMapper.class);
                fixtures.insertTenant(first, "model-reconciliation-fixture-a");
                fixtures.insertTenant(second, "model-reconciliation-fixture-b");
                var writer = new FixtureWriter(session.getMapper(AuditEventMapper.class));
                var mapper = session.getMapper(ModelCallAnalyticsMapper.class);
                LocalDate day = LocalDate.of(2046, 10, 10);
                AnalyticsPeriod period = period(day, ZONE);
                OffsetDateTime start = period.fromInclusive();
                PendingAuditEvent success = fact(first, start, "prompt.optimize", true, "RESPONSE_RECEIVED", false,
                        new TokenUsage(10L, 5L, 15L, 2L, 8L));
                assertThat(writer.insert(success)).isEqualTo(1);
                assertThat(writer.insert(success)).isZero(); // ack 丢失后的同 UUID 重放不能重复计量。
                writer.insert(fact(first, start.plusSeconds(1), "prompt.optimize", false, "RESPONSE_RECEIVED", false, new TokenUsage(7L, 3L, 10L)));
                writer.insert(fact(first, start.plusSeconds(2), "prompt.optimize", false, "STARTED", false, null));
                writer.insert(fact(first, start.plusSeconds(3), "prompt.optimize", true, "RESPONSE_RECEIVED", false, new TokenUsage(4L, null, 4L)));
                writer.insert(fact(first, start.plusSeconds(4), "prompt.optimize", false, "RESPONSE_RECEIVED", false, new TokenUsage(1L, 1L, 99L)));
                writer.insert(fact(first, start.plusSeconds(5), "prompt.optimize", true, "MOCK", true, new TokenUsage(100L, 0L, 100L)));
                writer.insert(fact(first, start.plusSeconds(6), "prompt.optimize", false, "NOT_SENT", false, new TokenUsage(200L, 0L, 200L)));
                writer.insert(fact(second, start.plusSeconds(7), "plan.generate", true, "RESPONSE_RECEIVED", false, new TokenUsage(2L, 1L, 3L)));
                // 起点包含，终点排除；归属按调用开始时刻，不因跨日完成而改为完成日。
                writer.insert(fact(first, start.minusSeconds(1), "prompt.optimize", true, "RESPONSE_RECEIVED", false, new TokenUsage(50L, 0L, 50L)));
                writer.insert(fact(first, period.toExclusive(), "prompt.optimize", true, "RESPONSE_RECEIVED", false, new TokenUsage(50L, 0L, 50L)));
                var tenantRanks = mapper.tokenRanking(period, first, "OPERATION", 20);
                assertThat(tenantRanks).singleElement().satisfies(rank -> {
                    assertThat(rank.source()).isEqualTo("prompt.optimize");
                    assertThat(rank.callCount()).isEqualTo(5);
                    assertThat(rank.completeUsageCount()).isEqualTo(2);
                    assertThat(rank.partialUsageCount()).isEqualTo(1);
                    assertThat(rank.unknownUsageCount()).isEqualTo(1);
                    assertThat(rank.invalidUsageCount()).isEqualTo(1);
                    assertThat(rank.inputTokens()).isEqualTo("21");
                    assertThat(rank.outputTokens()).isEqualTo("8");
                    assertThat(rank.totalTokens()).isEqualTo("29");
                    assertThat(rank.cacheHitTokens()).isEqualTo("2");
                    assertThat(rank.cacheMissTokens()).isEqualTo("8");
                    assertThat(rank.cacheKnownUsageCount()).isEqualTo(1);
                    assertThat(rank.cacheUnknownUsageCount()).isEqualTo(4);
                });
                assertThat(mapper.tokenRanking(period, null, "OPERATION", 20)).hasSize(2);
                assertThat(mapper.tokenRanking(period, first, "SOURCE", 1)).singleElement()
                        .satisfies(rank -> assertThat(rank.source()).isEqualTo("fixture-provider / fixture-model"));
                assertThat(mapper.tokenRanking(period, UUID.randomUUID(), "OPERATION", 20)).isEmpty();

                Page<ModelCallView> page = new Page<>(1, 2);
                page.setCountId("countModelCalls");
                var result = mapper.selectModelCalls(page, period, first, null, null);
                assertThat(result.getTotal()).isEqualTo(7);
                assertThat(result.getRecords()).hasSize(2).allSatisfy(call -> {
                    assertThat(call.tenantId()).isEqualTo(first);
                    assertThat(call.operation()).isEqualTo("prompt.optimize");
                });
                assertThat(result.getRecords().getFirst().transportState()).isEqualTo("NOT_SENT");
                assertThat(mapper.countModelCalls(period, second, "plan.generate", null)).isEqualTo(1);
                assertThat(mapper.countModelCalls(period, first, null, (String) success.details().get("requestId"))).isEqualTo(1);
                // 匿名事实不能膨胀账号活跃、使用频率、细分增强与 Plan 业务统计。
                var legacy = session.getMapper(AdminAnalyticsMapper.class);
                var counts = legacy.usageCounts(period, AnalyticsAccountFilter.forUser(null));
                assertThat(counts.accessCount()).isZero();
                assertThat(counts.activeUsers()).isZero();
                assertThat(counts.actualUsers()).isZero();
                assertThat(legacy.usageRanking(period, AnalyticsAccountFilter.forUser(null), 20)).isEmpty();
            } finally {
                session.rollback(true);
            }
        }
    }

    @Test
    void numericTotalsExceedLongSafelyAndMalformedMetadataDoesNotCauseSqlFailure() throws Exception {
        try (SqlSession session = openLocalSession()) {
            try {
                UUID tenant = UUID.randomUUID();
                session.getMapper(ModelCallFixtureMapper.class).insertTenant(tenant, "model-numeric-fixture");
                var writer = new FixtureWriter(session.getMapper(AuditEventMapper.class));
                var mapper = session.getMapper(ModelCallAnalyticsMapper.class);
                var period = period(LocalDate.of(2046, 3, 11), ZoneId.of("America/New_York"));
                for (int index = 0; index < 2; index++) writer.insert(fact(tenant, period.fromInclusive().plusSeconds(index),
                        "context.embedding", true, "RESPONSE_RECEIVED", false, new TokenUsage(Long.MAX_VALUE, 0L, Long.MAX_VALUE)));
                assertThat(mapper.tokenRanking(period, tenant, "OPERATION", 20)).singleElement()
                        .satisfies(rank -> assertThat(rank.totalTokens()).isEqualTo("18446744073709551614"));
                var malformed = new LinkedHashMap<>(fact(tenant, period.fromInclusive().plusSeconds(3), "plan.generate",
                        false, "RESPONSE_RECEIVED", false, null).details());
                malformed.put("inputTokens", "bad-text");
                malformed.put("outputTokens", -1);
                malformed.put("totalTokens", new java.math.BigInteger("999999999999999999999999999"));
                malformed.put("upstreamStatus", "not-status");
                malformed.put("prompt", "must never appear in API projection");
                writer.insert(new PendingAuditEvent(UUID.randomUUID(), tenant, null, AnalyticsEventType.MODEL_CALL_RECORDED,
                        malformed, period.fromInclusive().plusSeconds(3)));
                assertThatCode(() -> mapper.tokenRanking(period, tenant, "SOURCE", 20)).doesNotThrowAnyException();
                var detailPage = new Page<ModelCallView>(1, 20);
                detailPage.setCountId("countModelCalls");
                var records = mapper.selectModelCalls(detailPage, period, tenant, null, null).getRecords();
                assertThat(records).hasSize(3);
                assertThat(records.getFirst().inputTokens()).isNull();
                assertThat(records.getFirst().totalTokens()).isNull();
                assertThat(records.getFirst().usageState()).isEqualTo("INVALID");
                String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(records);
                assertThat(json).doesNotContain("must never appear", "actorUserId", "userId", "details", "prompt\"");
            } finally { session.rollback(true); }
        }
    }

    /** 使用与采集服务相同的白名单字段构造测试事实，不带账号或正文。 */
    private PendingAuditEvent fact(UUID tenant, OffsetDateTime at, String operation, boolean success,
                                   String transport, boolean mock, TokenUsage usage) {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 1);
        data.put("requestId", UUID.randomUUID().toString());
        data.put("workflowId", "0123456789abcdefabcd");
        data.put("operation", operation);
        data.put("providerRoute", "fixture-provider");
        data.put("model", "fixture-model");
        data.put("attempt", 1);
        data.put("mock", mock);
        data.put("successful", success);
        data.put("failureType", success ? null : "INVALID_RESPONSE");
        data.put("transportState", transport);
        data.put("usageState", usage == null ? "UNKNOWN" : usage.usageState());
        data.put("cacheState", usage == null ? "UNKNOWN" : usage.cacheState());
        data.put("durationMs", 1L);
        data.put("inputTokens", usage == null ? null : usage.inputTokens());
        data.put("outputTokens", usage == null ? null : usage.outputTokens());
        data.put("totalTokens", usage == null ? null : usage.totalTokens());
        data.put("cacheHitTokens", usage == null ? null : usage.cacheHitTokens());
        data.put("cacheMissTokens", usage == null ? null : usage.cacheMissTokens());
        return new PendingAuditEvent(UUID.randomUUID(), tenant, null, AnalyticsEventType.MODEL_CALL_RECORDED, data, at);
    }

    /** 自然日两端各自转换时区，保留 DST 日期长短与左闭右开过滤。 */
    private AnalyticsPeriod period(LocalDate day, ZoneId zone) {
        return new AnalyticsPeriod(day, day.plusDays(1), zone, day.atStartOfDay(zone).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }

    /** 仅从允许读取的开发配置与进程变量获取连接信息；不输出凭据、不接触 .env。 */
    private SqlSession openLocalSession() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());
        String url = resolve(properties.getProperty("spring.datasource.url"));
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("对账验收仅允许本机开发 PostgreSQL");
        }
        var source = new UnpooledDataSource("org.postgresql.Driver", url,
                resolve(properties.getProperty("spring.datasource.username")), resolve(properties.getProperty("spring.datasource.password")));
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("model-reconciliation", new JdbcTransactionFactory(), source));
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var interceptor = new MybatisPlusInterceptor();
        var pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        configuration.addInterceptor(interceptor);
        for (String path : List.of("mapper/analytics/AuditEventMapper.xml", "mapper/analytics/AdminAnalyticsMapper.xml",
                "mapper/analytics/ModelCallAnalyticsMapper.xml", "fixtures/ModelCallFixtureMapper.xml")) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(false);
    }

    /** 解析开发配置的单一环境变量占位符，禁止把解析值写入测试报告。 */
    private String resolve(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String[] parts = value.substring(2, value.length() - 1).split(":", 2);
            String supplied = System.getenv(parts[0]);
            return supplied != null ? supplied : (parts.length == 2 ? parts[1] : "");
        }
        return value;
    }

    /** 把不可变事实交给已有生产 Mapper，不增加注解 SQL 或测试专用生产签名。 */
    private record FixtureWriter(AuditEventMapper mapper) {
        int insert(PendingAuditEvent event) {
            return mapper.insert(event.id(), event.tenantId(), event.actorUserId(), event.eventType(), event.details(), event.occurredAt());
        }
    }
}
