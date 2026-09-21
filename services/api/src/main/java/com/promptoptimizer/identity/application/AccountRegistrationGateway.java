package com.promptoptimizer.identity.application;

import java.time.OffsetDateTime;

/**
 * 在一个事务内建立个人租户、账户、工作区、成员关系和首个登录身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface AccountRegistrationGateway {

    void createPersonalAccount(
            String normalizedEmail,
            String displayName,
            String passwordHash,
            OffsetDateTime verifiedAt
    );
}
