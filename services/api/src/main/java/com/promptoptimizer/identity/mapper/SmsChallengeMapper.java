package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.domain.SmsPurpose;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/**
 * 短信挑战窄持久化接口；状态条件在XML中执行，不暴露整行覆盖或物理删除。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface SmsChallengeMapper {
    /** 在短数据库事务内串行化同号码同用途的发码、核验预留与消费。 */
    String lockSubject(@Param("subject") String subject);
    /** 创建不含明文验证码和号码的挑战。 */
    int insert(SmsChallenge challenge);
    /** 按随机编号读取；应用必须另核对浏览器、用途和操作者。 */
    SmsChallenge find(@Param("id") UUID id);
    /** 替换未消费旧挑战；迟到云响应无法把它恢复。 */
    int supersede(@Param("phone") String phone, @Param("purpose") SmsPurpose purpose);
    /** 云端受理后的条件迁移。 */
    int markSent(@Param("id") UUID id);
    /** 单一核验者预留，并在调用云前累计尝试次数。 */
    int reserve(@Param("id") UUID id, @Param("token") UUID token);
    /** 应用云核验结果，必须匹配在途栅栏与未过期状态。 */
    int verified(@Param("id") UUID id, @Param("token") UUID token, @Param("passed") boolean passed);
    /** 结果不确定的发送或核验终止；不恢复发送预算。 */
    int fail(@Param("id") UUID id);
    /** 与账户写入同事务消费，并保存安全结果以恢复幂等请求。 */
    int consume(@Param("id") UUID id, @Param("userId") UUID userId, @Param("code") String code);
}
