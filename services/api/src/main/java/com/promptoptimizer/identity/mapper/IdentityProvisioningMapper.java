package com.promptoptimizer.identity.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 通过 XML 原子化创建账户关联记录，并维护受控的平台管理员用户名身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface IdentityProvisioningMapper {

    /** 锁定并读取一次性管理员初始化标记。 */
    Boolean selectAdminBootstrapConsumedForUpdate();

    /** 统计现有平台管理员账户。 */
    int countPlatformAdmins();

    /** 按活动邮箱身份定位与配置邮箱完全匹配的平台管理员账户。 */
    UUID selectActivePlatformAdminIdByEmailIdentity(@Param("normalizedEmail") String normalizedEmail);

    /** 为指定的平台管理员补建不存在的用户名身份，不覆盖已绑定身份。 */
    int insertUsernameIdentityIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                                       @Param("normalizedUsername") String normalizedUsername);

    /** 将一次性管理员初始化标记设为已消费。 */
    int markAdminBootstrapConsumed();

    /** 仅将指定的活动账户提升为平台管理员。 */
    int promoteActiveUserToAdmin(@Param("userId") UUID userId);

    /** 检查全局身份键是否已占用。 */
    boolean existsIdentity(
            @Param("identityType") String identityType,
            @Param("issuer") String issuer,
            @Param("normalizedIdentifier") String normalizedIdentifier
    );

    /** 创建个人租户。 */
    int insertTenant(@Param("id") UUID id, @Param("name") String name);

    /** 创建账户；平台角色由调用方显式传入合法代码。 */
    int insertUserAccount(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("platformRole") String platformRole
    );

    /** 创建隶属于指定租户的工作区。 */
    int insertWorkspace(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("createdBy") UUID createdBy
    );

    /** 创建工作区成员关系。 */
    int insertWorkspaceMember(
            @Param("workspaceId") UUID workspaceId,
            @Param("userId") UUID userId,
            @Param("role") String role
    );

    /** 将规范化登录键绑定到指定账户。 */
    int insertUserIdentity(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("identityType") String identityType,
            @Param("issuer") String issuer,
            @Param("identifier") String identifier,
            @Param("normalizedIdentifier") String normalizedIdentifier,
            @Param("status") String status,
            @Param("verifiedAt") OffsetDateTime verifiedAt
    );
}
