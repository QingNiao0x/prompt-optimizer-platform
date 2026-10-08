package com.promptoptimizer.identity.infrastructure.sms;

import com.promptoptimizer.identity.service.SmsException;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 使用独立业务密钥及领域分隔生成不可离线枚举的手机号、IP、浏览器指纹。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class SmsFingerprint {
    private final SmsProperties properties;
    public SmsFingerprint(SmsProperties properties) { this.properties = properties; }

    /** 不保存明文标识；不同 kind 使用独立摘要域，不能相互替代。 */
    public String of(String kind, String value) {
        byte[] secret = properties.getVerificationSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw SmsException.unavailable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((kind + "\n" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw SmsException.unavailable(); }
    }
}
