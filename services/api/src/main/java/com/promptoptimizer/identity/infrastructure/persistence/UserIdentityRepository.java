package com.promptoptimizer.identity.infrastructure.persistence;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录身份查询接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, UUID> {

    /** 按身份类型、发行方、规范化标识和状态查询登录身份。 */
    Optional<UserIdentityEntity> findByIdentityTypeAndIssuerAndNormalizedIdentifierAndStatus(
            UserIdentityType identityType,
            String issuer,
            String normalizedIdentifier,
            UserIdentityStatus status
    );

    /** 判断该身份标识是否已经被注册，避免重复绑定。 */
    boolean existsByIdentityTypeAndIssuerAndNormalizedIdentifier(
            UserIdentityType identityType,
            String issuer,
            String normalizedIdentifier
    );

    /** 按创建时间读取用户的指定状态身份，供账户资料或校验流程使用。 */
    List<UserIdentityEntity> findAllByUserIdAndStatusOrderByCreatedAtAsc(
            UUID userId,
            UserIdentityStatus status
    );
}
