-- 最小登录以邮箱作为全局登录标识。当前一个账户只属于一个租户；
-- 未来若改为“一个账户加入多个租户”，应拆分全局身份表和租户成员表，而不是放宽此约束。
CREATE UNIQUE INDEX uq_user_account_email_global
    ON user_account (lower(email));

COMMENT ON INDEX uq_user_account_email_global IS
    '登录邮箱全局唯一，确保无需信任客户端租户标识即可确定认证主体';
