package com.promptoptimizer.identity.sms;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 仅隔离验收数据库使用的状态/完整性夹具，SQL 保留在测试 Mapper XML。 */
@Mapper
public interface SmsFixtureMapper {
    Counts counts();
    /** 显式结果类型避开项目 Map/JSONB 全局处理器。 */
    record Counts(long tenants, long users, long workspaces, long members, long identities) { }
    int expire(@Param("id") UUID id, @Param("expiry") OffsetDateTime expiry);
    int accountStatus(@Param("id") UUID id, @Param("status") String status);
    int identityStatus(@Param("id") UUID id, @Param("status") String status);
    int history(@Param("id") UUID id, @Param("tenant") UUID tenant, @Param("workspace") UUID workspace, @Param("user") UUID user);
    String historyDigest();
}
