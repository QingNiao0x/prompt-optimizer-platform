package com.promptoptimizer.identity.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/**
 * 独立昵称验收数据库的状态夹具；全部SQL位于测试XML，不作为运行期Mapper发布。
 * @author QingNiao
 * @since 0.1.0
 */
public interface NicknameFixtureMapper {
    void putAvatar(@Param("id") UUID id, @Param("url") String url);
    void accountStatus(@Param("id") UUID id, @Param("status") String status);
    void identityStatus(@Param("id") UUID id, @Param("status") String status);
    void replaceProfile(@Param("id") UUID id, @Param("json") String json);
}
