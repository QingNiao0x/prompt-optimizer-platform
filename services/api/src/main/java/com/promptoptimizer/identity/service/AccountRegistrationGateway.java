package com.promptoptimizer.identity.service;

import java.time.OffsetDateTime;

/**
 * 在一个事务内建立个人租户、账户、工作区、成员关系和首个登录身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface AccountRegistrationGateway {

    /**
     * 原子地创建个人账户及默认工作区，避免注册只完成一部分。
     *
     * @param normalizedEmail 已规范化并完成验证码校验的邮箱
     * @param displayName 用户显示名称
     * @param passwordHash 单向哈希后的密码，不得传入明文
     * @param verifiedAt 邮箱完成验证的时间
     */
    void createPersonalAccount(
            String normalizedEmail,
            String displayName,
            String passwordHash,
            OffsetDateTime verifiedAt
    );
}
