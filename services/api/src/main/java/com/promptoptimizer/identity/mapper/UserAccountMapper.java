package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 平台账户的窄接口。只提供主键读取、默认工作区查询和密码哈希更新。
 * 不继承 {@code BaseMapper}，因此没有按主键物理删除或整行覆盖的入口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface UserAccountMapper {

    /**
     * 按主键读取账户。调用方须已从登录身份或当前认证主体得到用户 ID。
     */
    UserAccountEntity selectById(@Param("id") UUID id);

    /** 查询用户在指定租户中的活动 OWNER 工作区。 */
    UUID selectDefaultWorkspaceId(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    /**
     * 只更新指定账户的 BCrypt 哈希，并刷新资料更新时间。
     * 不修改状态、平台角色或租户。
     */
    int updatePasswordHash(@Param("id") UUID id, @Param("passwordHash") String passwordHash);
}
