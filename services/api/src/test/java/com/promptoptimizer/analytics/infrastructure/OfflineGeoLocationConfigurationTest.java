package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在不启动数据库和 Redis 的最小 Spring 上下文中验证离线解析器的默认资源和单一注入入口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OfflineGeoLocationConfigurationTest {

    @Test
    void injectsPrimaryResolverAndLoadsBundledDatabasesWithoutDeploymentSettings() {
        new ApplicationContextRunner().withUserConfiguration(ResolverConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            GeoLocationResolver selected = context.getBean(GeoLocationResolver.class);
            assertThat(selected).isInstanceOf(OfflineGeoLocationResolver.class);
            assertThat(context.getBean(ResolverConsumer.class).resolver()).isSameAs(selected);
            // 同一输入做修复前后的数据源对照：未配置 MMDB 时旧解析器为空，新入口仍有本地归属。
            assertThat(context.getBean(MaxMindGeoLocationResolver.class).resolve("114.114.114.114"))
                    .isEqualTo(GeoLocation.unavailable());
            assertThat(selected.resolve("114.114.114.114").country()).isNotBlank();
            assertThat(selected.resolve("2001:4860:4860::8888").country()).isNotBlank();
        });
    }

    @Test
    void unavailableOptionalMaxMindDatabaseStillUsesBundledFallback() {
        new ApplicationContextRunner().withUserConfiguration(ResolverConfiguration.class)
                .withPropertyValues("app.analytics.geoip.database=missing-optional-city-database.mmdb")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeoLocationResolver selected = context.getBean(GeoLocationResolver.class);
                    assertThat(selected.resolve("114.114.114.114").country()).isNotBlank();
                    assertThat(selected.resolve("127.0.0.1")).isEqualTo(GeoLocation.unavailable());
                });
    }

    /** 导入实际组件而非测试替身，确保默认 @Value 与 @Primary 在真实装配中生效。 */
    @Configuration(proxyBeanMethods = false)
    @Import({MaxMindGeoLocationResolver.class, Ip2RegionGeoLocationResolver.class,
            OfflineGeoLocationResolver.class})
    static class ResolverConfiguration {

        /** 模拟业务层按接口注入，防止新增多个实现后出现歧义启动失败。 */
        @Bean
        ResolverConsumer resolverConsumer(GeoLocationResolver resolver) {
            return new ResolverConsumer(resolver);
        }
    }

    /** 只保存解析接口，不依赖任何业务身份或持久化设施。 */
    record ResolverConsumer(GeoLocationResolver resolver) { }
}
