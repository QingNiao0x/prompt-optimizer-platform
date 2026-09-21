package com.promptoptimizer.identity.domain;

/**
 * 平台支持的登录身份类型。
 *
 * <p>类型只描述认证渠道；业务授权始终使用稳定的 userId，不直接使用邮箱、手机号或微信标识。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum UserIdentityType {
    EMAIL,
    PHONE,
    WECHAT
}
