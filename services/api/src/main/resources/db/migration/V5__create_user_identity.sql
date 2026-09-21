-- 将“用户账户”与“登录身份”拆分：一个稳定 user_account 可以绑定多个登录渠道。
-- 当前先迁移已有邮箱身份；PHONE / WECHAT 将在各自验证流程上线后写入。
CREATE TABLE user_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    identity_type VARCHAR(20) NOT NULL,
    issuer VARCHAR(120) NOT NULL,
    identifier VARCHAR(512) NOT NULL,
    normalized_identifier VARCHAR(320) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    verified_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_identity_type CHECK (identity_type IN ('EMAIL', 'PHONE', 'WECHAT')),
    CONSTRAINT ck_user_identity_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_user_identity_issuer_not_blank CHECK (btrim(issuer) <> ''),
    CONSTRAINT ck_user_identity_identifier_not_blank CHECK (btrim(identifier) <> ''),
    CONSTRAINT ck_user_identity_normalized_identifier_not_blank CHECK (btrim(normalized_identifier) <> '')
);

-- 同一登录身份在同一签发方下只能属于一个平台用户，防止跨账户重复绑定。
CREATE UNIQUE INDEX uq_user_identity_provider_subject
    ON user_identity (identity_type, issuer, normalized_identifier);

CREATE INDEX idx_user_identity_user_id
    ON user_identity (user_id);

INSERT INTO user_identity (
    id,
    user_id,
    identity_type,
    issuer,
    identifier,
    normalized_identifier,
    status,
    verified_at,
    created_at,
    updated_at
)
SELECT
    id,
    id,
    'EMAIL',
    'local',
    btrim(email),
    lower(btrim(email)),
    'ACTIVE',
    NULL,
    created_at,
    updated_at
FROM user_account
WHERE email IS NOT NULL AND btrim(email) <> '';

-- 邮箱唯一性从账户资料迁移到身份表；账户资料列暂时保留，兼容当前 API 与旧数据。
DROP INDEX uq_user_account_email_global;
DROP INDEX uq_user_account_tenant_email;
ALTER TABLE user_account ALTER COLUMN email DROP NOT NULL;

COMMENT ON TABLE user_identity IS '用户可用于认证的外部身份；一个用户可以绑定邮箱、手机号和微信等多个身份';
COMMENT ON COLUMN user_identity.user_id IS '身份归属的稳定平台用户标识';
COMMENT ON COLUMN user_identity.identity_type IS '身份类型：EMAIL、PHONE 或 WECHAT';
COMMENT ON COLUMN user_identity.issuer IS '身份签发方；本地邮箱和手机号使用 local，微信使用对应开放平台应用标识';
COMMENT ON COLUMN user_identity.identifier IS '原始标准化前的身份标识，仅保存登录查找所需值，不保存访问令牌';
COMMENT ON COLUMN user_identity.normalized_identifier IS '用于唯一约束和认证查找的规范化标识';
COMMENT ON COLUMN user_identity.verified_at IS '完成邮箱、短信或第三方授权验证的时间；历史账户迁移时可为空';
COMMENT ON INDEX uq_user_identity_provider_subject IS '同一签发方下的同一登录身份只能绑定一个平台用户';
