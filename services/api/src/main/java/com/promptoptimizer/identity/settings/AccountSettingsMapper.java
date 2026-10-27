package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/**
 * 系统设置窄持久化接口；只操作服务器确认的账户及其身份，不提供物理删除或所有权覆盖。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AccountSettingsMapper {
    /** 读取或锁定指定账户；已注销行仍供会话失效判断使用。 */
    UserAccountEntity account(@Param("id") UUID id, @Param("lock") boolean lock);
    /** 读取实际登录身份；写事务先锁账户行，会话校验只读，均须确认身份仍有效且归属正确。 */
    UserIdentityEntity identity(@Param("id") UUID id);
    /** 按类型读取本账户当前活动的本地身份，不使用联系邮箱代替认证身份。 */
    UserIdentityEntity activeIdentity(@Param("userId") UUID userId, @Param("type") String type);
    /** 全局检查占用，包含 REVOKED；不能把其他账户的身份转移给当前账户。 */
    UserIdentityEntity identityKey(@Param("type") String type, @Param("identifier") String identifier);
    /** 修改昵称；不会修改角色、密码或账户归属。 */
    int profile(@Param("id") UUID id, @Param("name") String name);
    /** 修改受数据库 CHECK 约束的界面语言。 */
    int locale(@Param("id") UUID id, @Param("locale") String locale);
    /** 撤销当前类型的旧身份，保留其唯一占用。 */
    int revokeType(@Param("id") UUID id, @Param("type") String type);
    /** 插入已验证新身份，冲突不覆盖原所有者，返回0由应用转换为409。 */
    int insertIdentity(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("type") String type, @Param("identifier") String identifier);
    /** 邮箱兼容字段同步和全设备版本递增须与身份替换同事务。 */
    int securityChange(@Param("id") UUID id, @Param("email") String email, @Param("updateEmail") boolean updateEmail);
    /** 逻辑注销并禁用账户，业务资源保持原归属。 */
    int deactivate(@Param("id") UUID id);
    /** 注销同时撤销该账户所有身份，不释放唯一键。 */
    int revokeAll(@Param("id") UUID id);
    /** 串行化最后一位平台管理员的注销检查，避免两个管理员同时通过。 */
    String lockAdminDeletion();
    /** 用于最后一位活动管理员保护，不把普通用户角色升级为平台角色。 */
    int activeAdminCount();
    /** 短事务原子消耗一个发送预算；冷却或小时额度不足返回0。 */
    int reserveBudget(@Param("key") String key, @Param("limit") int limit, @Param("cooldown") int cooldown);
    /** 串行化同一账户的挑战替换和发信结果回写。 */
    int supersede(@Param("userId") UUID userId);
    /** 保存无明文秘密的挑战，初始 PENDING 不能用于验证。 */
    int insertChallenge(EmailBindingChallenge challenge);
    /** SMTP 成功后才允许核验；迟到响应不会恢复已替换挑战。 */
    int sent(@Param("id") UUID id);
    /** 发送失败保留预算，不对不确定的供应商结果自动重发。 */
    int failed(@Param("id") UUID id);
    /** 锁定挑战后核对用途、操作者、目标和浏览器。 */
    EmailBindingChallenge challenge(@Param("id") UUID id);
    /** 每次错误在事务中持久化；第5次错误即失效。 */
    int badAttempt(@Param("id") UUID id);
    /** 与身份写入同事务进行一次性消费。 */
    int consume(@Param("id") UUID id);
}
