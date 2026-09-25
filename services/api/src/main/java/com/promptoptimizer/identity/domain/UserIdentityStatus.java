package com.promptoptimizer.identity.domain;

/**
 * 登录身份生命周期状态。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum UserIdentityStatus {
    /** 可用于认证查找的有效登录身份。 */
    ACTIVE,
    /** 已撤销绑定，不可再用于登录；保留记录供审计和唯一性约束使用。 */
    REVOKED
}
