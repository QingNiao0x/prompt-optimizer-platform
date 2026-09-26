package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.domain.UserIdentityType;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 登录身份的窄接口。只按类型、签发方和规范化标识查找。
 * 不继承 {@code BaseMapper}，因此没有按主键物理删除或整行覆盖的入口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface UserIdentityMapper {

    /** 按登录键读取一条身份；唯一索引保证至多一行。 */
    UserIdentityEntity selectByLoginKey(
            @Param("identityType") UserIdentityType identityType,
            @Param("issuer") String issuer,
            @Param("normalizedIdentifier") String normalizedIdentifier
    );

    /** 判断登录键是否已被占用，包含已吊销身份，避免同一标识再次注册。 */
    boolean existsByLoginKey(
            @Param("identityType") UserIdentityType identityType,
            @Param("issuer") String issuer,
            @Param("normalizedIdentifier") String normalizedIdentifier
    );
}
