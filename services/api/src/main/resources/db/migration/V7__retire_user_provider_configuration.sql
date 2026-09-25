-- 平台统一管理模型路由后，退役租户自定义供应商配置。
-- 应用本迁移会永久删除 provider_config 中的全部记录（包括加密后的用户 API Key）。
ALTER TABLE optimization_record
    DROP COLUMN provider_config_id;

DROP TABLE provider_config;
