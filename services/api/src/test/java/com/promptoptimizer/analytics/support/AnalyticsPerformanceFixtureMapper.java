package com.promptoptimizer.analytics.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 仅在性能验收的回滚事务中创建随机账户和审计事件，不迁移结构、不清理真实业务数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AnalyticsPerformanceFixtureMapper {

    /** 在随机租户下生成指定数量的账号，所有主键均由本次随机种子导出。 */
    int insertAccounts(@Param("tenantId") UUID tenantId, @Param("seed") String seed,
                       @Param("accounts") int accounts, @Param("start") OffsetDateTime start);

    /** 建立有效的本地邮箱身份，以验收真实的关键词账号过滤 SQL。 */
    int insertIdentities(@Param("seed") String seed, @Param("accounts") int accounts);

    /** 生成分散于一年、多个账户和七类事件的合成明细；不使用真实个人信息。 */
    int insertEvents(@Param("tenantId") UUID tenantId, @Param("seed") String seed,
                     @Param("accounts") int accounts, @Param("events") int events,
                     @Param("start") OffsetDateTime start);

    /** 在事务结束后确认随机租户、账号和事件均无残留。 */
    long remainingRows(@Param("tenantId") UUID tenantId);
}
