package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/**
 * 手机身份写入前的账户锁和归属核对；所有者仅由已认证主体或已验证PHONE身份取得。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface SmsAccountMapper {
    /** 账户锁串行化同账户的首次绑定并复查状态。 */
    UserAccountEntity lockAccount(@Param("id") UUID id);
    /** 锁定当前登录身份，避免撤销与绑定事务交错。 */
    UserIdentityEntity lockIdentity(@Param("id") UUID id);
    /** 读取当前账户唯一有效手机号，用于绑定幂等和安全资料投影。 */
    UserIdentityEntity activePhone(@Param("userId") UUID userId);
    /** 身份唯一冲突不更新所有者；返回0交由应用进行同账号/跨账号判断。 */
    int insertPhone(@Param("id") UUID id, @Param("userId") UUID userId, @Param("phone") String phone);
}
