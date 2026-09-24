-- 保留用户删除的历史记录，供审计和按保留策略进行后续清理。
ALTER TABLE optimization_record
    ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN optimization_record.deleted_at IS '逻辑删除时间；NULL 表示可见记录';

CREATE INDEX idx_optimization_record_active_tenant_workspace_created_at
    ON optimization_record (tenant_id, workspace_id, created_at DESC)
    WHERE deleted_at IS NULL;
