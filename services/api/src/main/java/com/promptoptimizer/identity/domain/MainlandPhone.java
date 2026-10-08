package com.promptoptimizer.identity.domain;

import com.promptoptimizer.identity.service.SmsException;

/**
 * 国内短信号码的入口规范化；数据库身份只使用 PHONE/local 和 E.164。
 * @author QingNiao
 * @since 0.1.0
 */
public final class MainlandPhone {
    private MainlandPhone() { }

    /** 接受大陆本地号码或 +86 号码，不把其他国际号码交给国内短信通道。 */
    public static String normalize(String input) {
        if (input == null || input.length() > 32) throw invalid();
        String value = input.trim().replace(" ", "").replace("-", "");
        if (value.startsWith("+86")) value = value.substring(3);
        if (!value.matches("1[3-9][0-9]{9}")) throw invalid();
        return "+86" + value;
    }

    /** 账户资料中只展示脱敏号码。 */
    public static String masked(String normalized) {
        String phone = normalize(normalized).substring(3);
        return "+86 " + phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static SmsException invalid() {
        return new SmsException(400, "PHONE_INVALID", "请输入有效的中国大陆手机号。");
    }
}
