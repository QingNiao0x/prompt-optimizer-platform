-- 首版只建立用户、工作区、优化历史、Provider 配置、用量和审计基础表。
-- 套餐、订阅、发票和配额账本属于商业化阶段，暂不进入本次迁移。

CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    tenant_type VARCHAR(20) NOT NULL DEFAULT 'PERSONAL',
    plan_code VARCHAR(40) NOT NULL DEFAULT 'FREE',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_type CHECK (tenant_type IN ('PERSONAL', 'TEAM')),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE UNIQUE INDEX uq_user_account_tenant_email
    ON user_account (tenant_id, lower(email));

CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES user_account (id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'))
);

CREATE INDEX idx_workspace_tenant_created_at
    ON workspace (tenant_id, created_at DESC);

CREATE TABLE workspace_member (
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT ck_workspace_member_role CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'))
);

CREATE UNIQUE INDEX uq_workspace_single_owner
    ON workspace_member (workspace_id)
    WHERE role = 'OWNER';

CREATE TABLE provider_config (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    workspace_id UUID REFERENCES workspace (id),
    created_by UUID NOT NULL REFERENCES user_account (id),
    provider_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    endpoint_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    key_version VARCHAR(40) NOT NULL,
    api_key_last4 VARCHAR(4),
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_provider_config_type CHECK (provider_type IN ('OPENAI', 'ANTHROPIC', 'DEEPSEEK', 'CUSTOM'))
);

CREATE INDEX idx_provider_config_scope
    ON provider_config (tenant_id, workspace_id, enabled);

CREATE TABLE optimization_session (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    created_by UUID NOT NULL REFERENCES user_account (id),
    title VARCHAR(160),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_optimization_session_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_optimization_session_workspace_updated_at
    ON optimization_session (workspace_id, updated_at DESC);

CREATE TABLE optimization_record (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    session_id UUID NOT NULL REFERENCES optimization_session (id),
    created_by UUID NOT NULL REFERENCES user_account (id),
    provider_config_id UUID REFERENCES provider_config (id),
    template_code VARCHAR(40) NOT NULL,
    raw_prompt TEXT NOT NULL,
    optimized_prompt TEXT NOT NULL,
    context_snapshot JSONB,
    result_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    permission_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    latency_ms INTEGER,
    retention_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_optimization_record_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_optimization_record_workspace_created_at
    ON optimization_record (workspace_id, created_at DESC);

CREATE INDEX idx_optimization_record_session_created_at
    ON optimization_record (session_id, created_at DESC);

CREATE TABLE usage_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    workspace_id UUID REFERENCES workspace (id),
    optimization_record_id UUID REFERENCES optimization_record (id),
    request_id VARCHAR(64) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms INTEGER,
    estimated_cost NUMERIC(18, 8),
    currency CHAR(3),
    usage_status VARCHAR(20) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usage_event_tokens CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
    ),
    CONSTRAINT ck_usage_event_status CHECK (usage_status IN ('COMPLETED', 'FAILED', 'INCOMPLETE'))
);

CREATE INDEX idx_usage_event_tenant_occurred_at
    ON usage_event (tenant_id, occurred_at);

CREATE INDEX idx_usage_event_request_id
    ON usage_event (request_id);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (id),
    actor_user_id UUID REFERENCES user_account (id),
    event_type VARCHAR(60) NOT NULL,
    resource_type VARCHAR(40),
    resource_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_event_tenant_occurred_at
    ON audit_event (tenant_id, occurred_at);

COMMENT ON TABLE tenant IS '租户表：个人账户或团队组织的计费与数据隔离主体';
COMMENT ON COLUMN tenant.id IS '租户唯一标识';
COMMENT ON COLUMN tenant.name IS '租户显示名称';
COMMENT ON COLUMN tenant.tenant_type IS '租户类型：个人或团队';
COMMENT ON COLUMN tenant.plan_code IS '当前套餐编码，MVP 阶段仅保留基础标识';
COMMENT ON COLUMN tenant.status IS '租户状态：启用、暂停或已删除';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '最后更新时间';

COMMENT ON TABLE user_account IS '用户账户表：保存登录标识和基础资料，不保存明文密码';
COMMENT ON COLUMN user_account.id IS '用户唯一标识';
COMMENT ON COLUMN user_account.tenant_id IS '所属租户标识';
COMMENT ON COLUMN user_account.email IS '登录邮箱，业务层写入前统一转为小写';
COMMENT ON COLUMN user_account.display_name IS '用户显示名称';
COMMENT ON COLUMN user_account.password_hash IS '密码哈希，禁止保存明文密码';
COMMENT ON COLUMN user_account.status IS '用户状态：启用、锁定或禁用';
COMMENT ON COLUMN user_account.last_login_at IS '最近登录时间';
COMMENT ON COLUMN user_account.created_at IS '创建时间';
COMMENT ON COLUMN user_account.updated_at IS '最后更新时间';

COMMENT ON TABLE workspace IS '工作区表：隔离项目上下文、提示词历史和团队成员';
COMMENT ON COLUMN workspace.id IS '工作区唯一标识';
COMMENT ON COLUMN workspace.tenant_id IS '所属租户标识';
COMMENT ON COLUMN workspace.name IS '工作区名称';
COMMENT ON COLUMN workspace.description IS '工作区说明';
COMMENT ON COLUMN workspace.preferences IS '工作区偏好设置，例如默认模板和上下文策略';
COMMENT ON COLUMN workspace.created_by IS '创建用户标识';
COMMENT ON COLUMN workspace.status IS '工作区状态：启用、归档或已删除';
COMMENT ON COLUMN workspace.created_at IS '创建时间';
COMMENT ON COLUMN workspace.updated_at IS '最后更新时间';

COMMENT ON TABLE workspace_member IS '工作区成员表：记录用户在工作区中的角色';
COMMENT ON COLUMN workspace_member.workspace_id IS '工作区标识';
COMMENT ON COLUMN workspace_member.user_id IS '用户标识';
COMMENT ON COLUMN workspace_member.role IS '成员角色：所有者、编辑者或查看者';
COMMENT ON COLUMN workspace_member.joined_at IS '加入工作区时间';
COMMENT ON COLUMN workspace_member.updated_at IS '成员角色最后更新时间';

COMMENT ON TABLE provider_config IS '模型供应商配置表：保存加密后的 API Key 和模型参数';
COMMENT ON COLUMN provider_config.id IS 'Provider 配置唯一标识';
COMMENT ON COLUMN provider_config.tenant_id IS '所属租户标识';
COMMENT ON COLUMN provider_config.workspace_id IS '所属工作区，NULL 表示租户级配置';
COMMENT ON COLUMN provider_config.created_by IS '创建用户标识';
COMMENT ON COLUMN provider_config.provider_type IS '供应商类型，例如 DeepSeek 或自定义兼容端点';
COMMENT ON COLUMN provider_config.display_name IS '配置显示名称';
COMMENT ON COLUMN provider_config.endpoint_url IS '完整的模型 API 请求地址';
COMMENT ON COLUMN provider_config.model_name IS '模型名称';
COMMENT ON COLUMN provider_config.api_key_ciphertext IS '使用 AES-256-GCM 加密后的 API Key，不保存明文';
COMMENT ON COLUMN provider_config.key_version IS '加密主密钥版本，用于密钥轮换';
COMMENT ON COLUMN provider_config.api_key_last4 IS 'API Key 末四位，仅用于界面确认';
COMMENT ON COLUMN provider_config.parameters IS '模型参数，例如温度和最大输出 Token 数';
COMMENT ON COLUMN provider_config.enabled IS '是否允许业务请求使用该配置';
COMMENT ON COLUMN provider_config.created_at IS '创建时间';
COMMENT ON COLUMN provider_config.updated_at IS '最后更新时间';

COMMENT ON TABLE optimization_session IS '优化会话表：组织用户的一组相关提示词优化操作';
COMMENT ON COLUMN optimization_session.id IS '优化会话唯一标识';
COMMENT ON COLUMN optimization_session.tenant_id IS '所属租户标识';
COMMENT ON COLUMN optimization_session.workspace_id IS '所属工作区标识';
COMMENT ON COLUMN optimization_session.created_by IS '发起用户标识';
COMMENT ON COLUMN optimization_session.title IS '会话标题';
COMMENT ON COLUMN optimization_session.status IS '会话状态：进行中或已归档';
COMMENT ON COLUMN optimization_session.created_at IS '创建时间';
COMMENT ON COLUMN optimization_session.updated_at IS '最后更新时间';

COMMENT ON TABLE optimization_record IS '优化记录表：保存用户主动保留的提示词优化历史';
COMMENT ON COLUMN optimization_record.id IS '优化记录唯一标识';
COMMENT ON COLUMN optimization_record.tenant_id IS '所属租户标识';
COMMENT ON COLUMN optimization_record.workspace_id IS '所属工作区标识';
COMMENT ON COLUMN optimization_record.session_id IS '所属优化会话标识';
COMMENT ON COLUMN optimization_record.created_by IS '发起用户标识';
COMMENT ON COLUMN optimization_record.provider_config_id IS '使用的 Provider 配置，Mock 场景允许为空';
COMMENT ON COLUMN optimization_record.template_code IS '使用的提示词模板编码';
COMMENT ON COLUMN optimization_record.raw_prompt IS '用户原始提示词，仅在保存历史时持久化';
COMMENT ON COLUMN optimization_record.optimized_prompt IS '模型生成的结构化优化提示词';
COMMENT ON COLUMN optimization_record.context_snapshot IS '截断和脱敏后的项目上下文摘要';
COMMENT ON COLUMN optimization_record.result_metadata IS '段落、歧义和约束等结构化结果元数据';
COMMENT ON COLUMN optimization_record.permission_policy IS '权限红线和人工确认规则';
COMMENT ON COLUMN optimization_record.latency_ms IS '本次优化耗时，单位为毫秒';
COMMENT ON COLUMN optimization_record.retention_until IS '本条记录的保留截止时间';
COMMENT ON COLUMN optimization_record.created_at IS '创建时间';

COMMENT ON TABLE usage_event IS '用量事件表：记录模型调用的 Token、耗时和估算成本事实';
COMMENT ON COLUMN usage_event.id IS '用量事件唯一标识';
COMMENT ON COLUMN usage_event.tenant_id IS '计费用租户标识';
COMMENT ON COLUMN usage_event.workspace_id IS '所属工作区标识';
COMMENT ON COLUMN usage_event.optimization_record_id IS '关联的优化记录标识';
COMMENT ON COLUMN usage_event.request_id IS '平台请求标识，用于日志排查';
COMMENT ON COLUMN usage_event.provider_type IS '模型供应商类型';
COMMENT ON COLUMN usage_event.model_name IS '实际调用的模型名称';
COMMENT ON COLUMN usage_event.input_tokens IS '输入 Token 数，上游未提供时为 NULL';
COMMENT ON COLUMN usage_event.output_tokens IS '输出 Token 数，上游未提供时为 NULL';
COMMENT ON COLUMN usage_event.latency_ms IS 'Provider 调用耗时，单位为毫秒';
COMMENT ON COLUMN usage_event.estimated_cost IS '估算成本，不代表最终账单金额';
COMMENT ON COLUMN usage_event.currency IS '成本货币代码，例如 USD';
COMMENT ON COLUMN usage_event.usage_status IS '用量状态：完成、失败或数据不完整';
COMMENT ON COLUMN usage_event.metadata IS '非敏感的供应商返回元数据';
COMMENT ON COLUMN usage_event.occurred_at IS '用量事件发生时间';

COMMENT ON TABLE audit_event IS '审计事件表：记录配置变更和管理操作，不保存敏感正文';
COMMENT ON COLUMN audit_event.id IS '审计事件唯一标识';
COMMENT ON COLUMN audit_event.tenant_id IS '所属租户标识';
COMMENT ON COLUMN audit_event.actor_user_id IS '执行操作的用户标识，系统任务可为空';
COMMENT ON COLUMN audit_event.event_type IS '审计事件类型';
COMMENT ON COLUMN audit_event.resource_type IS '被操作资源类型';
COMMENT ON COLUMN audit_event.resource_id IS '被操作资源标识';
COMMENT ON COLUMN audit_event.details IS '脱敏后的变更摘要';
COMMENT ON COLUMN audit_event.occurred_at IS '审计事件发生时间';
