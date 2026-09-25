-- 平台级模型目录独立于已退役的租户供应商配置；密钥和端点仍由服务端持有。
ALTER TABLE user_account
    ADD COLUMN platform_role VARCHAR(24) NOT NULL DEFAULT 'USER';

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_platform_role
        CHECK (platform_role IN ('USER', 'PLATFORM_ADMIN'));

CREATE TABLE platform_model (
    id UUID PRIMARY KEY,
    public_id VARCHAR(160) NOT NULL UNIQUE,
    route_key VARCHAR(60) NOT NULL,
    upstream_model VARCHAR(120) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    default_model BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_platform_model_sort_order CHECK (sort_order >= 0)
);

CREATE UNIQUE INDEX uq_platform_model_active_route_name
    ON platform_model (route_key, upstream_model)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_platform_model_default
    ON platform_model (default_model)
    WHERE default_model = TRUE AND deleted_at IS NULL;

CREATE INDEX idx_platform_model_visible
    ON platform_model (enabled, sort_order, public_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE platform_model IS '管理员维护的平台可选模型目录；不保存端点或 API Key';
COMMENT ON COLUMN platform_model.public_id IS '终端用户提交的稳定模型标识';
COMMENT ON COLUMN platform_model.deleted_at IS '逻辑删除时间，保留既有调用的模型身份';

-- 记录显式管理员初始化已执行，防止角色撤销后因遗留配置在重启时重新授予。
CREATE TABLE platform_admin_bootstrap (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    consumed_at TIMESTAMPTZ
);

INSERT INTO platform_admin_bootstrap (singleton_id, consumed) VALUES (1, FALSE);
