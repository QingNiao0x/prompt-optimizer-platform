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

    Optional<UserIdentityEntity> findByIdentityTypeAndIssuerAndNormalizedIdentifierAndStatus(
            UserIdentityType identityType,
            String issuer,
            String normalizedIdentifier,
            UserIdentityStatus status
    );

    List<UserIdentityEntity> findAllByUserIdAndStatusOrderByCreatedAtAsc(
            UUID userId,
            UserIdentityStatus status
    );
}
