package com.promptoptimizer.identity.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 登录身份的规范化查找键。
 *
 * <p>邮箱忽略大小写，手机号只接受已经转换为 E.164 的值，第三方身份标识保持大小写。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record UserIdentityKey(
        UserIdentityType type,
        String issuer,
        String normalizedIdentifier
) {

    public static final String LOCAL_ISSUER = "local";
    private static final Pattern E164_PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    public UserIdentityKey {
        type = Objects.requireNonNull(type, "type must not be null");
        issuer = requireText(issuer, "issuer");
        normalizedIdentifier = normalize(type, requireText(normalizedIdentifier, "identifier"));
    }

    /** 构造本地邮箱身份键；邮箱规范化在记录构造时统一完成。 */
    public static UserIdentityKey email(String email) {
        return new UserIdentityKey(UserIdentityType.EMAIL, LOCAL_ISSUER, email);
    }

    /** 构造本地手机号身份键，拒绝不符合 E.164 格式的号码。 */
    public static UserIdentityKey phone(String e164PhoneNumber) {
        return new UserIdentityKey(UserIdentityType.PHONE, LOCAL_ISSUER, e164PhoneNumber);
    }

    /** 构造第三方身份键，保留提供方主体标识的大小写。 */
    public static UserIdentityKey wechat(String appId, String providerSubject) {
        return new UserIdentityKey(UserIdentityType.WECHAT, appId, providerSubject);
    }

    private static String normalize(UserIdentityType type, String identifier) {
        String trimmed = identifier.trim();
        if (type == UserIdentityType.EMAIL) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (type == UserIdentityType.PHONE && !E164_PHONE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("phone identity must use E.164 format");
        }
        return trimmed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
