package com.promptoptimizer.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户登录身份持久化映射，对应 user_identity 表。
 * 每条记录把一种登录标识绑定到稳定平台账户；身份唯一性按类型、签发方和规范化标识约束。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@TableName("user_identity")
public class UserIdentityEntity {

    /** 登录身份绑定关系主键。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属账户主键，外键引用 user_account.id。应用层不物理删除账户；保留期清理若删除账户行，数据库会级联清理身份。 */
    private UUID userId;

    /** 登录渠道；合法值及当前支持状态见 {@link UserIdentityType}，与数据库 CHECK 一致。 */
    private UserIdentityType identityType;

    /** 身份签发方；本地身份使用 local，第三方身份使用对应平台应用标识。 */
    private String issuer;

    /** 登录查找所需的原始身份标识；不保存第三方访问令牌。 */
    private String identifier;

    /** 规范化后的查找值，参与唯一索引；邮箱和用户名采用小写形式。 */
    private String normalizedIdentifier;

    /** 身份生命周期状态；合法值及含义见 {@link UserIdentityStatus}，与数据库 CHECK 一致。 */
    private UserIdentityStatus status;

    /** 邮箱、短信或第三方身份完成验证的时间；用户名身份可为空。 */
    private OffsetDateTime verifiedAt;

    /** 最近一次成功使用该身份认证的时间；尚未登录时为空。 */
    private OffsetDateTime lastUsedAt;

    /** 身份绑定创建时间，由数据库默认值维护。 */
    private OffsetDateTime createdAt;

    /** 身份绑定最后更新时间，由数据库或更新回调维护。 */
    private OffsetDateTime updatedAt;

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
