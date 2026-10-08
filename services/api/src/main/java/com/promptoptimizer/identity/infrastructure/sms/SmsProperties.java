package com.promptoptimizer.identity.infrastructure.sms;

import com.promptoptimizer.identity.domain.SmsPurpose;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 非凭据短信配置；不包含 AccessKey，避免配置绑定和诊断意外暴露云凭据。
 * @author QingNiao
 * @since 0.1.0
 */
@ConfigurationProperties("app.security.sms")
public class SmsProperties {
    private boolean enabled;
    private String signName = "";
    private String schemePrefix = "";
    private String verificationSecret = "";
    private String accountTemplate = "100001";
    private String bindingTemplate = "100004";
    private int phoneHourlyLimit = 5;
    private int ipHourlyLimit = 20;
    private int actorHourlyLimit = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getSignName() { return signName; }
    public void setSignName(String value) { signName = value; }
    public String getSchemePrefix() { return schemePrefix; }
    public void setSchemePrefix(String value) { schemePrefix = value; }
    public String getVerificationSecret() { return verificationSecret; }
    public void setVerificationSecret(String value) { verificationSecret = value; }
    public String getAccountTemplate() { return accountTemplate; }
    public void setAccountTemplate(String value) { accountTemplate = value; }
    public String getBindingTemplate() { return bindingTemplate; }
    public void setBindingTemplate(String value) { bindingTemplate = value; }
    public int getPhoneHourlyLimit() { return phoneHourlyLimit; }
    public void setPhoneHourlyLimit(int value) { phoneHourlyLimit = value; }
    public int getIpHourlyLimit() { return ipHourlyLimit; }
    public void setIpHourlyLimit(int value) { ipHourlyLimit = value; }
    public int getActorHourlyLimit() { return actorHourlyLimit; }
    public void setActorHourlyLimit(int value) { actorHourlyLimit = value; }

    /** 不同用途和部署环境隔离，避免同号码的验证码跨用途互认。 */
    public String scheme(SmsPurpose purpose) { return schemePrefix + "-" + purpose.name().toLowerCase(java.util.Locale.ROOT); }
}
