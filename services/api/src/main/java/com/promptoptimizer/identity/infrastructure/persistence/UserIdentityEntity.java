package com.promptoptimizer.identity.infrastructure.persistence;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 用户登录身份持久化映射；登录身份与稳定平台账户分离。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Entity
@Table(name = "user_identity")
public class UserIdentityEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 20)
    private UserIdentityType identityType;

    @Column(name = "issuer", nullable = false, length = 120)
    private String issuer;

    @Column(name = "identifier", nullable = false, length = 512)
    private String identifier;

    @Column(name = "normalized_identifier", nullable = false, length = 320)
    private String normalizedIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserIdentityStatus status;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UserIdentityType getIdentityType() {
        return identityType;
    }

    public void setIdentityType(UserIdentityType identityType) {
        this.identityType = identityType;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getNormalizedIdentifier() {
        return normalizedIdentifier;
    }

    public void setNormalizedIdentifier(String normalizedIdentifier) {
        this.normalizedIdentifier = normalizedIdentifier;
    }

    public UserIdentityStatus getStatus() {
        return status;
    }

    public void setStatus(UserIdentityStatus status) {
        this.status = status;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(OffsetDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
