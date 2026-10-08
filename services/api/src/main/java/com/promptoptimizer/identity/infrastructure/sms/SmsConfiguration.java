package com.promptoptimizer.identity.infrastructure.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import com.promptoptimizer.identity.service.SmsException;
import com.promptoptimizer.identity.service.SmsVerificationProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 默认关闭短信；开启时验证私密配置和日志边界，凭据只允许读取后端进程环境。
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfiguration {
    /** 不配置短信时不读取凭据、不创建云客户端、不影响原有邮箱登录。 */
    @Bean
    SmsVerificationProvider smsVerificationProvider(SmsProperties properties, Environment environment) {
        if (!properties.isEnabled()) return new SmsVerificationProvider() {
            public void send(String phone, String scheme, String template, String id) { throw SmsException.unavailable(); }
            public boolean verify(String phone, String scheme, String code, String id) { throw SmsException.unavailable(); }
        };
        validate(properties, environment);
        String keyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        String keySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException("短信已启用，但后端进程缺少 ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET；请检查环境变量并重启IDE。");
        }
        try {
            Config config = new Config().setAccessKeyId(keyId).setAccessKeySecret(keySecret)
                    .setEndpoint("dypnsapi.aliyuncs.com").setProtocol("https");
            var provider = new AliyunPnvsProvider(new Client(config), properties);
            org.slf4j.LoggerFactory.getLogger(SmsConfiguration.class)
                    .info("event=sms.configuration.checked credentialsConfigured=true mode=pnvs");
            return provider;
        } catch (Exception exception) {
            throw new IllegalStateException("短信客户端初始化失败，请检查服务端配置；凭据未输出。");
        }
    }

    /** 只报告配置项是否缺失，不把实际值放进异常、Bean名称或日志。 */
    public static void validate(SmsProperties properties, Environment environment) {
        if (properties.getSignName().isBlank() || !properties.getSchemePrefix().matches("[a-zA-Z0-9_-]{1,11}")
                || properties.getVerificationSecret().getBytes(StandardCharsets.UTF_8).length < 32
                || properties.getAccountTemplate().isBlank() || properties.getBindingTemplate().isBlank()
                || properties.getPhoneHourlyLimit() < 1 || properties.getIpHourlyLimit() < 1 || properties.getActorHourlyLimit() < 1) {
            throw new IllegalStateException("短信配置不完整：需要签名、1至11位环境方案前缀、至少32字节业务密钥和有效限流配置。");
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("local-sql-debug")
                || Arrays.asList(environment.getActiveProfiles()).contains("local-mock")
                || environment.getProperty("decorator.datasource.enabled", Boolean.class, false)
                || environment.getProperty("spring.datasource.url", "").contains(":p6spy:")) {
            throw new IllegalStateException("真实短信不能与 local-sql-debug、local-mock 或 SQL 参数打印同时启用。");
        }
        if (!environment.getProperty("app.security.login-guard.require-redis", Boolean.class, true)
                || environment.getProperty("mybatis-plus.configuration.log-impl", "").contains("StdOutImpl")) {
            throw new IllegalStateException("真实短信要求 Redis 登录防护，并禁止 MyBatis 标准输出参数日志。");
        }
        LoggingSystem logging = LoggingSystem.get(SmsConfiguration.class.getClassLoader());
        // 检查已单独配置的语句级 Logger，避免仅关闭包级日志却遗漏 Mapper 方法日志。
        for (var configured : logging.getLoggerConfigurations()) {
            String name = configured.getName();
            if ((name.startsWith("com.promptoptimizer.identity") || name.startsWith("com.aliyun")
                    || name.startsWith("okhttp3") || name.startsWith("org.apache.http") || name.startsWith("p6spy"))
                    && (configured.getEffectiveLevel() == LogLevel.DEBUG || configured.getEffectiveLevel() == LogLevel.TRACE)) {
                throw new IllegalStateException("真实短信禁止身份Mapper或SDK的详细日志。");
            }
        }
        for (String name : List.of("ROOT", "com.promptoptimizer.identity", "com.promptoptimizer.identity.mapper",
                "com.promptoptimizer.identity.mapper.UserIdentityMapper", "com.promptoptimizer.identity.mapper.IdentityProvisioningMapper",
                "com.promptoptimizer.identity.mapper.SmsChallengeMapper", "com.promptoptimizer.identity.mapper.SmsAccountMapper",
                "com.aliyun", "com.aliyun.tea", "com.aliyun.dypnsapi20170525", "okhttp3", "org.apache.http")) {
            var config = logging.getLoggerConfiguration(name);
            if (config != null && (config.getEffectiveLevel() == LogLevel.DEBUG || config.getEffectiveLevel() == LogLevel.TRACE)) {
                throw new IllegalStateException("真实短信不允许身份Mapper、云SDK或HTTP详细日志，请关闭相关 DEBUG/TRACE。");
            }
        }
    }
}
