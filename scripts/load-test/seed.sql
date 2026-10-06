-- 仅可向新建的 budget_load 压测数据库执行；不修改现有业务库。
-- 合成用户复用测试演示账户的 BCrypt 哈希；不包含任何明文凭据。
DO $$ BEGIN
    IF current_database() <> 'budget_load' THEN
        RAISE EXCEPTION '只能向隔离的 budget_load 数据库写入压测夹具';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM user_account WHERE email = 'demo@local'
                   AND password_hash IS NOT NULL) THEN
        RAISE EXCEPTION '必须先由应用初始化随机测试密码';
    END IF;
END $$;
BEGIN;
INSERT INTO tenant (id, name, tenant_type, plan_code, status)
SELECT md5('budget-tenant-' || n)::uuid, 'Budget load ' || n, 'PERSONAL', 'FREE', 'ACTIVE'
FROM generate_series(1, 110) n ON CONFLICT (id) DO NOTHING;
INSERT INTO user_account (id, tenant_id, email, display_name, password_hash, status)
SELECT md5('budget-user-' || n)::uuid, md5('budget-tenant-' || n)::uuid,
       'budget-' || n || '@load.invalid', 'Load user ' || n, demo.password_hash, 'ACTIVE'
FROM generate_series(1, 110) n
CROSS JOIN (SELECT password_hash FROM user_account WHERE email = 'demo@local') demo
ON CONFLICT (id) DO NOTHING;
INSERT INTO workspace (id, tenant_id, name, created_by, status)
SELECT md5('budget-workspace-' || n)::uuid, md5('budget-tenant-' || n)::uuid,
       'Load workspace ' || n, md5('budget-user-' || n)::uuid, 'ACTIVE'
FROM generate_series(1, 110) n ON CONFLICT (id) DO NOTHING;
INSERT INTO workspace_member (workspace_id, user_id, role)
SELECT md5('budget-workspace-' || n)::uuid, md5('budget-user-' || n)::uuid, 'OWNER'
FROM generate_series(1, 110) n ON CONFLICT (workspace_id, user_id) DO NOTHING;
INSERT INTO user_identity (id, user_id, identity_type, issuer, identifier,
                           normalized_identifier, status, verified_at)
SELECT md5('budget-identity-' || n)::uuid, md5('budget-user-' || n)::uuid,
       'EMAIL', 'local', 'budget-' || n || '@load.invalid',
       'budget-' || n || '@load.invalid', 'ACTIVE', CURRENT_TIMESTAMP
FROM generate_series(1, 110) n ON CONFLICT (id) DO NOTHING;
COMMIT;
SELECT count(*) AS synthetic_users FROM user_identity
WHERE normalized_identifier LIKE 'budget-%@load.invalid';
