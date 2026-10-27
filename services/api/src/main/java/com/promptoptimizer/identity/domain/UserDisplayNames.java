package com.promptoptimizer.identity.domain;

/**
 * 注册与第三方资料共用的显示昵称规则；昵称只用于展示，不参与登录或身份唯一性判断。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class UserDisplayNames {
    /** 与当前VARCHAR(80)及浏览器maxlength保持一致，用户输入超限应拒绝而非静默截断。 */
    public static final int MAX_LENGTH = 80;
    /** 昵称为单行展示资料，允许中文和表情；排除换行及其他控制字符。 */
    public static final String SINGLE_LINE_PATTERN = "[^\\p{Cc}]*";

    private UserDisplayNames() { }

    /** 缺省或全空白表示未填写；保留内部空格、中文及表情，不截断用户输入。 */
    public static String optional(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    /** 应用服务也复核长度与控制字符，避免非HTTP调用绕过DTO校验；缺省沿用调用方的原有默认名。 */
    public static String resolve(String value, String fallback) {
        String normalized = optional(value);
        if (normalized == null) return fallback;
        if (normalized.length() > MAX_LENGTH) throw new IllegalArgumentException("昵称不能超过80个字符。");
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("昵称不能包含换行或控制字符。");
        }
        return normalized;
    }
}
