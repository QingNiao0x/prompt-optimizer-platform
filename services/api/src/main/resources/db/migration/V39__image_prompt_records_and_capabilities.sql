-- 独立业务记录不改变文本优化契约。原图仅进入受控临时文件，不能写入本表 JSON。
CREATE TABLE image_prompt_record (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    user_id UUID NOT NULL REFERENCES user_account(id),
    mode VARCHAR(32) NOT NULL CHECK (mode IN ('REFERENCE_RECONSTRUCTION', 'CUSTOM_CREATION')),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    optimized_prompt TEXT NOT NULL CHECK (length(optimized_prompt) BETWEEN 1 AND 30000),
    edit_revision BIGINT NOT NULL DEFAULT 0 CHECK (edit_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_image_prompt_record_scope ON image_prompt_record (tenant_id, workspace_id, user_id, created_at DESC) WHERE deleted_at IS NULL;
COMMENT ON TABLE image_prompt_record IS '当前用户、租户与工作区内私有的图像提示词记录；保存文字结果及来源摘要，不保存原图；业务删除为逻辑删除';
COMMENT ON COLUMN image_prompt_record.id IS '生成操作 UUID，同时作为幂等记录主键；重试保存不覆盖已有或已删除结果';
COMMENT ON COLUMN image_prompt_record.tenant_id IS '所属 tenant.id；服务端身份确定，外键默认禁止物理删除被引用租户';
COMMENT ON COLUMN image_prompt_record.workspace_id IS '所属 workspace.id；必须与服务端工作区及租户范围共同校验，外键默认限制物理删除';
COMMENT ON COLUMN image_prompt_record.user_id IS '私有记录所有者 user_account.id；查询、编辑、再次优化和逻辑删除均核对，外键默认限制物理删除';
COMMENT ON COLUMN image_prompt_record.mode IS '必填业务模式，无默认值：REFERENCE_RECONSTRUCTION=关闭Plan还原参考图，CUSTOM_CREATION=开启Plan辅助新图创作；无预留代码';
COMMENT ON COLUMN image_prompt_record.payload IS '必填JSON对象，无默认值；当前键为operationId/recordId/mode/optimizedPrompt/analysis/warnings/input/sources/questions/answers/modelId/modelName/mock/editRevision/historySaved/persistentHistory/createdAt；analysis为带图片UUID、类别、描述和状态的观察数组，sources为图片元数据数组，questions/answers为实际题答数组，input为创作要求/模式/图片引用/文字摘要/输出偏好对象；无图时图片数组为空，不保存图片字节/公开图片URL；数据库仅校验顶层对象，不强制键或内部结构；当前正文与修订以独立列为准';
COMMENT ON COLUMN image_prompt_record.optimized_prompt IS '用户当前可复制提示词正文；1至30000个数据库字符；编辑不调用模型';
COMMENT ON COLUMN image_prompt_record.edit_revision IS '非负并发编辑修订号，默认0；保存时匹配预期版本，成功加1';
COMMENT ON COLUMN image_prompt_record.created_at IS '生成时间，带时区时刻，默认数据库当前时刻；应用以UTC传入';
COMMENT ON COLUMN image_prompt_record.updated_at IS '最近编辑或删除的带时区时刻，默认数据库当前时刻';
COMMENT ON COLUMN image_prompt_record.deleted_at IS '逻辑删除的带时区时刻；NULL表示未删除，默认NULL；所有业务读取排除非NULL值';

CREATE TABLE image_prompt_model_capability (
    model_id VARCHAR(160) PRIMARY KEY REFERENCES platform_model(public_id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    max_images INTEGER NOT NULL DEFAULT 1 CHECK (max_images BETWEEN 1 AND 3),
    evidence VARCHAR(400) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE image_prompt_model_capability IS '管理员显式维护的平台模型视觉输入准入；跟随现有模型逻辑删除状态，非用户数据，不保存密钥或端点';
COMMENT ON COLUMN image_prompt_model_capability.model_id IS '引用platform_model.public_id的稳定模型身份；默认外键限制物理删除，模型逻辑删除后不再返回此能力';
COMMENT ON COLUMN image_prompt_model_capability.enabled IS '是否允许图像业务传入图片，默认false；声明能力不等于所有图片或质量验收通过';
COMMENT ON COLUMN image_prompt_model_capability.max_images IS '单次调用允许的图片数量，默认1，CHECK允许1至3；平台仍执行字节和像素限制';
COMMENT ON COLUMN image_prompt_model_capability.evidence IS '管理员填写的官方能力依据或受控验证记录，必填无默认值，最长400字符；不得含凭据或用户图片内容';
COMMENT ON COLUMN image_prompt_model_capability.updated_at IS '最近更新准入的带时区时刻，默认数据库当前时刻';
