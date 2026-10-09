package com.promptoptimizer.analytics;

import com.promptoptimizer.identity.infrastructure.registration.RegistrationConfiguration;
import com.promptoptimizer.identity.infrastructure.sms.SmsConfiguration;
import com.promptoptimizer.identity.infrastructure.sms.SmsProperties;
import com.promptoptimizer.identity.service.SmsVerificationProvider;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查分析验收自身的配置隔离，避免部署环境中的真实投递开关污染 Maven 测试。
 * 只加载投递配置，不连接数据库、Redis 或云服务，也不读取真实凭据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AnalyticsAcceptanceConfigurationTest {

    @Test
    void ordinaryTestsKeepRealDeliveryDisabledByDefault() throws Exception {
        var defaults = new ResourcePropertySource("testDefaults", "classpath:application.properties");
        new ApplicationContextRunner()
                .withUserConfiguration(SmsConfiguration.class, RegistrationConfiguration.class)
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(defaults);
                    context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                            "simulatedDeployment", Map.of(
                            "SMS_ENABLED", "true", "EMAIL_DELIVERY_MODE", "smtp",
                            "app.security.sms.enabled", "${SMS_ENABLED}",
                            "app.security.registration.delivery-mode", "${EMAIL_DELIVERY_MODE}"
                    )));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SmsProperties.class).isEnabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("app.security.registration.delivery-mode"))
                            .isEqualTo("disabled");
                });
    }

    @Test
    void acceptanceOverridesRealDeliverySettingsFromDeploymentEnvironment() {
        SpringBootTest acceptance = AnalyticsLocalDatabaseAcceptanceTest.class.getAnnotation(SpringBootTest.class);
        assertThat(acceptance).isNotNull();

        new ApplicationContextRunner()
                .withUserConfiguration(SmsConfiguration.class, RegistrationConfiguration.class)
                .withPropertyValues(acceptance.properties())
                .withInitializer(context -> {
                    // 模拟环境变量对应的部署配置，优先级低于真实验收类的显式测试属性。
                    // 使用占位值；开启分支应先触发 Redis 安全校验，不触及 AccessKey 或网络。
                    context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                            "simulatedDeployment", Map.of(
                            "app.security.sms.enabled", "true",
                            "app.security.sms.sign-name", "TEST_PLACEHOLDER",
                            "app.security.sms.scheme-prefix", "test",
                            "app.security.sms.verification-secret", "test-only-independent-secret-at-least-32-bytes",
                            "app.security.registration.delivery-mode", "smtp"
                    )));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SmsVerificationProvider.class);
                    assertThat(context).hasSingleBean(VerificationEmailSender.class);
                    assertThat(context.getBean(SmsProperties.class).isEnabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("app.security.registration.delivery-mode"))
                            .isEqualTo("disabled");
                });
    }
}
