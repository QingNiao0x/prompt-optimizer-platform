CREATE TABLE text_polish_record (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    user_id UUID NOT NULL REFERENCES user_account(id),
    source_record_id UUID REFERENCES text_polish_record(id),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    polished_text TEXT NOT NULL CHECK (length(polished_text) BETWEEN 1 AND 24000),
    edit_revision BIGINT NOT NULL DEFAULT 0 CHECK (edit_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_text_polish_scope ON text_polish_record (tenant_id, workspace_id, user_id, created_at DESC) WHERE deleted_at IS NULL;
COMMENT ON TABLE text_polish_record IS '文章祛AI味私有润色记录；按用户、租户、工作区隔离，生成快照与可编辑正文分存，业务删除为逻辑删除';
COMMENT ON COLUMN text_polish_record.id IS '客户端本次生成操作UUID；幂等插入不覆盖已编辑或已删除的记录';
COMMENT ON COLUMN text_polish_record.tenant_id IS '所属tenant.id；来自服务端身份，默认外键限制物理删除';
COMMENT ON COLUMN text_polish_record.workspace_id IS '所属workspace.id；读取和写入同时核对租户与用户，默认外键限制物理删除';
COMMENT ON COLUMN text_polish_record.user_id IS '记录所有者user_account.id；管理员统计权限不授予正文读取权，默认外键限制物理删除';
COMMENT ON COLUMN text_polish_record.source_record_id IS '重新生成或继续润色所依据的text_polish_record.id；首次输入时NULL，服务端验证同一用户/租户/工作区、未删除及修订号，默认外键限制物理删除';
COMMENT ON COLUMN text_polish_record.payload IS '必填JSON对象，无默认值；当前键id/input/generatedText/polishedText/sourceRecordId/sourceMode/modelId/modelName/mock/editRevision/checks/historySaved/persistentHistory/createdAt；input含sourceText/style/depth/audience/requirements/lockedTexts/modelId，lockedTexts为原文精确保留字符串数组；checks为code/message/before/after对象数组。generatedText保留生成原件，当前正文和修订以独立列为准。数据库仅检查顶层对象，不强制内部键和结构';
COMMENT ON COLUMN text_polish_record.polished_text IS '当前润色正文，1至24000个数据库字符；手动编辑不调用模型';
COMMENT ON COLUMN text_polish_record.edit_revision IS '非负编辑修订号，默认0；保存须匹配预期版本后加1';
COMMENT ON COLUMN text_polish_record.created_at IS '生成时刻，TIMESTAMPTZ，默认数据库当前时刻；应用按UTC传入';
COMMENT ON COLUMN text_polish_record.updated_at IS '最近编辑或逻辑删除时刻，TIMESTAMPTZ，默认数据库当前时刻';
COMMENT ON COLUMN text_polish_record.deleted_at IS '逻辑删除时刻，TIMESTAMPTZ；默认NULL表示未删除，所有业务读取排除非NULL';

CREATE TABLE text_polish_model_policy (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    enabled_model_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(enabled_model_ids) = 'array'),
    default_model_id VARCHAR(160) REFERENCES platform_model(public_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE text_polish_model_policy IS '平台管理员统一模型管理中的文章润色准入及独立默认模型；仅引用平台目录，不保存供应商端点或凭据';
COMMENT ON COLUMN text_polish_model_policy.id IS '单例配置键，仅允许1，无默认值';
COMMENT ON COLUMN text_polish_model_policy.enabled_model_ids IS '非空JSON字符串数组，默认空数组表示关闭本模块模型；元素是platform_model.public_id，由应用验证存在且未删除，数据库只校验顶层数组；全局停用或删除仍优先生效';
COMMENT ON COLUMN text_polish_model_policy.default_model_id IS '引用platform_model.public_id的模块默认模型；未开放模型时NULL，由应用校验属于enabled_model_ids；默认外键限制物理删除，全局默认模型不受本配置影响';
COMMENT ON COLUMN text_polish_model_policy.updated_at IS '最近管理员更新配置的TIMESTAMPTZ时刻，默认数据库当前时刻';
INSERT INTO text_polish_model_policy (id, enabled_model_ids, default_model_id)
SELECT 1, COALESCE(jsonb_agg(public_id ORDER BY sort_order, public_id) FILTER (WHERE enabled), '[]'::jsonb),
       max(public_id) FILTER (WHERE enabled AND default_model)
FROM platform_model WHERE deleted_at IS NULL;
