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
    /** 邮箱登录身份，通过电子邮件地址查找账户并完成密码认证。 */
    EMAIL,
    /** 手机号身份；短信开关控制注册、普通用户短信登录及首次绑定，密码登录需显式指定类型。 */
    PHONE,
    /** 微信登录身份，预留微信开放平台授权后的第三方登录；当前未开放微信认证流程。 */
    WECHAT,
    /** 用户名登录身份，通过自定义用户名查找账户并完成密码认证。 */
    USERNAME
}
