-- 管理员首启账户需要用户名登录；邮箱身份仍保持兼容。
ALTER TABLE user_identity
    DROP CONSTRAINT ck_user_identity_type;

ALTER TABLE user_identity
    ADD CONSTRAINT ck_user_identity_type
        CHECK (identity_type IN ('EMAIL', 'PHONE', 'WECHAT', 'USERNAME'));

ALTER TABLE user_identity
    ADD CONSTRAINT ck_user_identity_username_format
        CHECK (
            identity_type <> 'USERNAME'
            OR (
                issuer = 'local'
                AND normalized_identifier = lower(normalized_identifier)
                AND normalized_identifier ~ '^[a-z0-9][a-z0-9._-]{2,31}$'
            )
        );

COMMENT ON COLUMN user_identity.identity_type IS '身份类型：EMAIL、PHONE、WECHAT 或 USERNAME';
COMMENT ON COLUMN user_identity.normalized_identifier IS '用于唯一约束和认证查找的规范化标识；用户名使用小写 ASCII 格式';
