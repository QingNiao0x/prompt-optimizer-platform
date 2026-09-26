package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.EmailVerificationPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮箱注册、验证码和邮件投递配置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "app.security.registration")
public class RegistrationProperties {

    private boolean enabled = true;
    private String deliveryMode = "disabled";
    private String verificationSecret = "";
    private String fromAddress = "";
    private Duration codeTtl = Duration.ofMinutes(5);
    private Duration resendInterval = Duration.ofSeconds(60);

    @Min(1)
    @Max(20)
    private int maxAttempts = 5;

    @Min(1)
    @Max(100)
    private int emailHourlyLimit = 5;

    @Min(1)
    @Max(1000)
    private int ipHourlyLimit = 20;

    private boolean requireRedis = true;

    /** 将配置属性整理成发码与校验共用的不可变策略。 */
    public EmailVerificationPolicy policy() {
        return new EmailVerificationPolicy(
                codeTtl,
                resendInterval,
                maxAttempts,
                emailHourlyLimit,
                ipHourlyLimit
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(String deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public String getVerificationSecret() {
        return verificationSecret;
    }

    public void setVerificationSecret(String verificationSecret) {
        this.verificationSecret = verificationSecret;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public Duration getResendInterval() {
        return resendInterval;
    }

    public void setResendInterval(Duration resendInterval) {
        this.resendInterval = resendInterval;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getEmailHourlyLimit() {
        return emailHourlyLimit;
    }

    public void setEmailHourlyLimit(int emailHourlyLimit) {
        this.emailHourlyLimit = emailHourlyLimit;
    }

    public int getIpHourlyLimit() {
        return ipHourlyLimit;
    }

    public void setIpHourlyLimit(int ipHourlyLimit) {
        this.ipHourlyLimit = ipHourlyLimit;
    }

    public boolean isRequireRedis() {
        return requireRedis;
    }

    public void setRequireRedis(boolean requireRedis) {
        this.requireRedis = requireRedis;
    }
}
