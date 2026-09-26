package com.promptoptimizer.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 平台用户账户持久化映射，对应 user_account 表。
 * 账户归属于单一租户；可绑定多个 user_identity，联系邮箱允许为空，密码仅保存单向哈希。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@TableName("user_account")
public class UserAccountEntity {

    /** 平台账户主键，被身份、成员关系和历史记录作为用户关联键引用。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属租户主键，隔离该账户可访问的租户数据。 */
    private UUID tenantId;

    /** 可空联系邮箱；登录身份及其唯一性由 user_identity 管理。 */
    private String email;

    /** 账户展示名称，不参与认证查找。 */
    private String displayName;

    /** BCrypt 密码哈希；不得保存或返回明文密码。 */
    private String passwordHash;

    /** ACTIVE=可登录；LOCKED=暂时锁定；DISABLED=禁用账户。取值与数据库 CHECK 一致。 */
    private String status;

    /** USER=普通平台用户；PLATFORM_ADMIN=平台管理员。取值与数据库 CHECK 一致，且独立于工作区角色。 */
    private String platformRole = "USER";

    /** 最近一次成功登录时间；从未登录时为空。 */
    private OffsetDateTime lastLoginAt;

    /** 账户资料最后更新时间，由持久化更新时刷新。 */
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlatformRole() {
        return platformRole;
    }

    public void setPlatformRole(String platformRole) {
        this.platformRole = platformRole;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(OffsetDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
