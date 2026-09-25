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
 * 用户登录身份持久化映射，对应 user_identity 表。
 * 每条记录把一种登录标识绑定到稳定平台账户；身份唯一性按类型、签发方和规范化标识约束。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Entity
@Table(name = "user_identity")
public class UserIdentityEntity {

    /** 登录身份绑定关系主键。 */
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** 所属账户主键，外键引用 user_account.id；删除账户时身份关系随之删除。 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 登录渠道；合法值及当前支持状态见 {@link UserIdentityType}，与数据库 CHECK 一致。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 20)
    private UserIdentityType identityType;

    /** 身份签发方；本地身份使用 local，第三方身份使用对应平台应用标识。 */
    @Column(name = "issuer", nullable = false, length = 120)
    private String issuer;

    /** 登录查找所需的原始身份标识；不保存第三方访问令牌。 */
    @Column(name = "identifier", nullable = false, length = 512)
    private String identifier;

    /** 规范化后的查找值，参与唯一索引；邮箱和用户名采用小写形式。 */
    @Column(name = "normalized_identifier", nullable = false, length = 320)
    private String normalizedIdentifier;

    /** 身份生命周期状态；合法值及含义见 {@link UserIdentityStatus}，与数据库 CHECK 一致。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserIdentityStatus status;

    /** 邮箱、短信或第三方身份完成验证的时间；用户名身份可为空。 */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    /** 最近一次成功使用该身份认证的时间；尚未登录时为空。 */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    /** 身份绑定创建时间，由数据库默认值维护。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 身份绑定最后更新时间，由数据库或更新回调维护。 */
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
