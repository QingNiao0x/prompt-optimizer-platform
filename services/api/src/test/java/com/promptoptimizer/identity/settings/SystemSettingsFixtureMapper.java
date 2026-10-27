package com.promptoptimizer.identity.settings;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 仅供隔离PostgreSQL验收使用的合成夹具；不连接业务库，不清除真实账户或历史。
 * SQL集中在测试Mapper XML，检查注销前后的账户与业务关联完整性。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface SystemSettingsFixtureMapper {
    /** 添加一条属于合成账号原租户、工作区的历史会话。 */
    void history(@Param("id") UUID id, @Param("tenant") UUID tenant, @Param("workspace") UUID workspace,
                 @Param("user") UUID user);
    /** 返回持久化账户与业务关联的原始状态；仅供测试断言，不能作为公开接口。 */
    Snapshot snapshot(@Param("user") UUID user);
    /** 模拟管理员撤销身份，以验证旧会话的请求级拒绝。 */
    void revoke(@Param("user") UUID user);
    /** 模拟时间推进，不等待五分钟或改变运行环境时钟。 */
    void expire(@Param("id") UUID id);
    /** 读取消费和错误次数，验证事务提交而非仅检查HTTP成功。 */
    ChallengeSnapshot challenge(@Param("id") UUID id);

    /** 明确标量列类型，避免全局JSONB对象处理器误处理通用Map结果。 */
    record Snapshot(String status, OffsetDateTime deleted, long version, String locale,
                    long identities, long histories, long memberships) { }
    /** 挑战只暴露断言需要的状态，不读取验证码摘要。 */
    record ChallengeSnapshot(String status, int attempts, OffsetDateTime consumed) { }
}
