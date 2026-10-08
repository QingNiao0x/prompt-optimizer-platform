package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 读取实际 YAML 验证 SMTP 兼容与隐私边界；隔离系统环境且不执行连接测试或发送邮件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RegistrationConfigurationTest {

    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
    private LogLevel originalRootLevel;

    @BeforeEach
    void useSafeRootLogging() {
        originalRootLevel = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    @AfterEach
    void restoreRootLogging() {
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, originalRootLevel);
    }

    @Test
    void shouldKeepDeliveryDisabledWhenProfileIsNotSelected() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(VerificationEmailSender.class);
            // Spring Boot 按 host 配置项是否存在创建客户端；空 host 不代表开放邮件投递。
            assertThat(context.getBean(VerificationEmailSender.class)).isNotInstanceOf(SmtpVerificationEmailSender.class);
            assertThat(context.getBean(RegistrationProperties.class).getDeliveryMode()).isEqualTo("disabled");
            assertThatThrownBy(() -> context.getBean(VerificationEmailSender.class)
                    .send("recipient@example.test", "012345", Duration.ofMinutes(5)))
                    .isInstanceOf(RegistrationException.class);
        });
    }

    @Test
    void shouldPreserveGenericStarttlsSettingsWhenUsingExistingSmtpConfiguration() {
        runner(false).withPropertyValues("EMAIL_DELIVERY_MODE=smtp", "SMTP_HOST=smtp.example.test",
                "SMTP_USERNAME=noreply@example.test", "SMTP_PASSWORD=legacy-test-password").run(context -> {
            assertThat(context).hasNotFailed();
            JavaMailSenderImpl client = context.getBean(JavaMailSenderImpl.class);
            assertThat(client.getHost()).isEqualTo("smtp.example.test");
            assertThat(client.getPort()).isEqualTo(587);
            assertThat(client.getPassword()).isEqualTo("legacy-test-password");
            assertThat(client.getDefaultEncoding()).isEqualTo("UTF-8");
            assertThat(client.getJavaMailProperties()).containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.ssl.enable", "false")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
            assertThat(context.getBean(RegistrationProperties.class).getFromAddress()).isEqualTo("noreply@example.test");
        });
    }

    @Test
    void shouldConfigureAliyunImplicitTlsWhenProfileIsSelected() {
        runner(true).withPropertyValues("SMTP_USERNAME=noreply@example.test", "ALIBABA_SMTP_PASSWORD=aliyun-test-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(VerificationEmailSender.class)).isInstanceOf(SmtpVerificationEmailSender.class);
                    JavaMailSenderImpl client = context.getBean(JavaMailSenderImpl.class);
                    assertThat(client.getHost()).isEqualTo("smtpdm.aliyun.com");
                    assertThat(client.getPort()).isEqualTo(465);
                    assertThat(client.getUsername()).isEqualTo("noreply@example.test");
                    assertThat(client.getPassword()).isEqualTo("aliyun-test-password");
                    assertThat(client.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
                            .containsEntry("mail.smtp.ssl.enable", "true")
                            .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                            .containsEntry("mail.smtp.starttls.enable", "false")
                            .containsEntry("mail.smtp.starttls.required", "false")
                            .containsEntry("mail.debug", "false")
                            .containsEntry("mail.debug.auth", "false")
                            .containsEntry("mail.smtp.connectiontimeout", "5000")
                            .containsEntry("mail.smtp.timeout", "10000")
                            .containsEntry("mail.smtp.writetimeout", "10000");
                });
    }

    @Test
    void shouldLoadAliyunProfileThroughSpringBootConfigData() {
        isolatedRunner().withPropertyValues("spring.config.location=classpath:application.yml",
                        "spring.profiles.active=aliyun-smtp", "SMTP_USERNAME=noreply@example.test",
                        "ALIBABA_SMTP_PASSWORD=aliyun-test-password")
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles()).contains("aliyun-smtp");
                    JavaMailSenderImpl client = context.getBean(JavaMailSenderImpl.class);
                    assertThat(client.getHost()).isEqualTo("smtpdm.aliyun.com");
                    assertThat(client.getPort()).isEqualTo(465);
                    assertThat(client.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                            .containsEntry("mail.smtp.starttls.enable", "false");
                    assertThat(context.getBean(VerificationEmailSender.class)).isInstanceOf(SmtpVerificationEmailSender.class);
                });
    }

    @Test
    void shouldPreferAliyunPasswordWhenBothVariablesExist() {
        runner(true).withPropertyValues("ALIBABA_SMTP_PASSWORD=aliyun-test-password", "SMTP_PASSWORD=legacy-test-password")
                .run(context -> assertThat(context.getBean(JavaMailSenderImpl.class).getPassword())
                        .isEqualTo("aliyun-test-password"));
    }

    @Test
    void shouldUseLegacyPasswordWhenAliyunVariableIsAbsent() {
        runner(true).withPropertyValues("SMTP_PASSWORD=legacy-test-password")
                .run(context -> assertThat(context.getBean(JavaMailSenderImpl.class).getPassword())
                        .isEqualTo("legacy-test-password"));
    }

    @Test
    void shouldNotSelectAnotherPasswordWhenAliyunVariableIsExplicitlyEmpty() {
        runner(true).withPropertyValues("ALIBABA_SMTP_PASSWORD=", "SMTP_PASSWORD=legacy-test-password")
                .run(context -> assertThat(context.getBean(JavaMailSenderImpl.class).getPassword()).isEmpty());
    }

    @Test
    void shouldRespectDisabledDeliveryWhenEnvironmentOverridesAliyunProfile() {
        runner(true).withPropertyValues("EMAIL_DELIVERY_MODE=disabled").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RegistrationProperties.class).getDeliveryMode()).isEqualTo("disabled");
            assertThat(context.getBean(VerificationEmailSender.class)).isNotInstanceOf(SmtpVerificationEmailSender.class);
        });
    }

    @Test
    void shouldKeepLocalLogModeWhenExplicitlySelected() {
        runner(false).withPropertyValues("EMAIL_DELIVERY_MODE=log").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(VerificationEmailSender.class);
            assertThat(context.getBean(VerificationEmailSender.class)).isNotInstanceOf(SmtpVerificationEmailSender.class);
        });
    }

    @Test
    void shouldSupportManualSslConfigurationWithoutAliyunProfile() {
        runner(false).withPropertyValues("EMAIL_DELIVERY_MODE=smtp", "SMTP_HOST=smtpdm.aliyun.com",
                "SMTP_PORT=465", "SMTP_SSL_ENABLED=true", "SMTP_STARTTLS=false").run(context -> {
            assertThat(context).hasNotFailed();
            JavaMailSenderImpl client = context.getBean(JavaMailSenderImpl.class);
            assertThat(client.getPort()).isEqualTo(465);
            assertThat(client.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                    .containsEntry("mail.smtp.starttls.enable", "false")
                    .containsEntry("mail.smtp.starttls.required", "false");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local-sql-debug", "local-mock"})
    void shouldRejectSensitiveProfileWhenRealSmtpIsEnabled(String profile) {
        runner(true).withInitializer(context -> context.getEnvironment().setActiveProfiles("aliyun-smtp", profile))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "真实 SMTP 邮件不能与 local-sql-debug、local-mock 或 SQL 参数打印同时启用。");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"decorator.datasource.enabled=true", "spring.datasource.url=jdbc:p6spy:postgresql://localhost/test",
            "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl"})
    void shouldRejectSqlParameterPrintingWhenRealSmtpIsEnabled(String setting) {
        runner(true).withPropertyValues(setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "真实 SMTP 邮件不能与 local-sql-debug、local-mock 或 SQL 参数打印同时启用。");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.mail.properties.mail.debug=true", "spring.mail.properties.mail.debug.auth=true"})
    void shouldRejectProtocolDebugWhenRealSmtpIsEnabled(String setting) {
        runner(true).withPropertyValues(setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "真实 SMTP 邮件禁止开启 mail.debug / mail.debug.auth，避免泄露验证码和认证信息。");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOT", "com.promptoptimizer.identity.mapper.UserIdentityMapper.existsByLoginKey", "org.eclipse.angus.mail.smtp"})
    void shouldRejectEffectiveVerboseLoggerWhenRealSmtpIsEnabled(String loggerName) {
        var previous = logging.getLoggerConfiguration(loggerName);
        LogLevel level = previous == null ? null : previous.getConfiguredLevel();
        logging.setLogLevel(loggerName, LogLevel.DEBUG);
        try {
            runner(true).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "真实 SMTP 邮件禁止身份 Mapper、邮件客户端或根 Logger 的 DEBUG/TRACE 日志。");
            });
        } finally {
            logging.setLogLevel(loggerName, level);
        }
    }

    /** 只加载邮件相关 Bean 与真实 YAML，隔离开发者凭据，不执行自动连接测试。 */
    private ApplicationContextRunner runner(boolean aliyun) {
        return isolatedRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    try {
                        var base = loader.load("smtp-base", new ClassPathResource("application.yml"));
                        base.forEach(sources::addLast);
                        if (aliyun) {
                            context.getEnvironment().setActiveProfiles("aliyun-smtp");
                            for (var profile : loader.load("smtp-aliyun", new ClassPathResource("application-aliyun-smtp.yml"))) {
                                sources.addBefore(base.getFirst().getName(), profile);
                            }
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException("测试无法加载 SMTP 配置文件。", exception);
                    }
                });
    }

    /** ConfigData 测试使用干净环境，避免手动加入的 YAML 覆盖 Spring Boot 自身的 profile 顺序。 */
    private ApplicationContextRunner isolatedRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(RegistrationConfiguration.class)
                .withPropertyValues("spring.mail.test-connection=false")
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                });
    }
}
