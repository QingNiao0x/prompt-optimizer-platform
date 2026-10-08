package com.promptoptimizer.identity.domain;

/**
 * 短信挑战用途；与数据库 CHECK 保持一致，不开放换绑或密码重置。
 * @author QingNiao
 * @since 0.1.0
 */
public enum SmsPurpose {
    REGISTER, LOGIN, BIND
}
