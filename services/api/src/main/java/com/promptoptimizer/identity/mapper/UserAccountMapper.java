package com.promptoptimizer.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 平台账户单表访问。主键查询和更新走 MyBatis-Plus；默认工作区是跨表查询，仍放在 XML。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    /** 查询用户在指定租户中的活动 OWNER 工作区。 */
    UUID selectDefaultWorkspaceId(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    /** 只更新指定账户的 BCrypt 哈希，并刷新资料更新时间。 */
    int updatePasswordHash(@Param("id") UUID id, @Param("passwordHash") String passwordHash);
}
