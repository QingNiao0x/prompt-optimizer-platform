package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.domain.UserIdentityType;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 登录身份的窄接口。按登录键查找，并为已验证微信回调提供限定的展示资料同步。
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

    /** 按已验证的微信应用和主体同步昵称；仅活动身份及活动所属账户可更新，不转移归属或改变显示名/角色。 */
    int syncWechatNickname(@Param("issuer") String issuer, @Param("subject") String subject,
                           @Param("nickname") String nickname, @Param("syncedAt") String syncedAt);
}
