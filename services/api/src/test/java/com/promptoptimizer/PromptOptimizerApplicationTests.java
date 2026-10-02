package com.promptoptimizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.flyway.enabled=false",
                "app.history.enabled=false"
        }
)
class PromptOptimizerApplicationTests {

    @MockBean
    private com.promptoptimizer.identity.mapper.UserAccountMapper userAccountMapper;

    @MockBean
    private com.promptoptimizer.identity.mapper.UserIdentityMapper userIdentityMapper;

    @MockBean
    private com.promptoptimizer.identity.mapper.IdentityProvisioningMapper identityProvisioningMapper;

    @MockBean
    private com.promptoptimizer.history.mapper.OptimizationRecordMapper optimizationRecordMapper;

    @MockBean
    private com.promptoptimizer.history.mapper.OptimizationSessionMapper optimizationSessionMapper;

    @MockBean
    private com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper adminAnalyticsMapper;

    @MockBean
    private com.promptoptimizer.analytics.mapper.AuditEventMapper auditEventMapper;

    @MockBean
    private com.promptoptimizer.payment.mapper.RechargeRecordMapper rechargeRecordMapper;

    @MockBean
    private com.promptoptimizer.provider.mapper.PlatformModelMapper platformModelMapper;

    // 全量测试的类路径也包含数据库验收夹具；此启动测试显式关闭数据库，不能创建真实 Mapper。
    @MockBean
    private com.promptoptimizer.analytics.support.AnalyticsAcceptanceMapper analyticsAcceptanceMapper;

    @MockBean
    private com.promptoptimizer.analytics.support.AnalyticsAggregationFixtureMapper analyticsAggregationFixtureMapper;

    @MockBean
    private com.promptoptimizer.analytics.support.AnalyticsPerformanceFixtureMapper analyticsPerformanceFixtureMapper;

    @Test
    void contextLoads() {
    }
}
