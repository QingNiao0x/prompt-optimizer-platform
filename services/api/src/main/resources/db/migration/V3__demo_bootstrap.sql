-- MVP 本地演示数据：为尚未实现登录鉴权的阶段提供固定的租户、用户和工作区。
-- 接入真实用户体系后，应移除或改为按部署环境初始化。

INSERT INTO tenant (id, name, tenant_type, plan_code, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'Local Demo Tenant', 'PERSONAL', 'FREE', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_account (id, tenant_id, email, display_name, password_hash, status)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'demo@local',
    'Local Demo User',
    NULL,
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace (id, tenant_id, name, description, preferences, created_by, status)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    'Local Demo Workspace',
    'MVP 本地联调使用的默认工作区',
    '{}'::jsonb,
    '00000000-0000-0000-0000-000000000002',
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_member (workspace_id, user_id, role)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000002',
    'OWNER'
)
ON CONFLICT (workspace_id, user_id) DO NOTHING;
