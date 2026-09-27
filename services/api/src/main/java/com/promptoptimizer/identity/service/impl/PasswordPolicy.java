package com.promptoptimizer.identity.service.impl;

import java.nio.charset.StandardCharsets;

/**
 * 注册和初始化密码的统一规则：至少 8 位，且同时包含字母和数字。
 *
 * <p>登录校验不套用这套复杂度，避免已有的仅字母或仅数字密码无法登录。BCrypt 只使用前 72 字节。</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {
    }

    /** 判断密码是否满足长度、字母和数字要求。 */
    public static boolean meets(String password) {
        if (password == null || password.length() < MIN_LENGTH || utf8Length(password) > MAX_BCRYPT_BYTES) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int index = 0; index < password.length(); ) {
            int codePoint = password.codePointAt(index);
            letter = letter || Character.isLetter(codePoint);
            digit = digit || Character.isDigit(codePoint);
            index += Character.charCount(codePoint);
        }
        return letter && digit;
    }

    /** 返回面向用户的失败说明；调用方只在 {@link #meets(String)} 为 false 时使用。 */
    public static String rejectionMessage() {
        return "密码至少 8 个字符，且须同时包含字母和数字，UTF-8 编码后不能超过 72 字节。";
    }

    private static int utf8Length(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }
}
