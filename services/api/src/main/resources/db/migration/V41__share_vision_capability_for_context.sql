-- 视觉准入继续由模型管理维护；沿用既有表名，增加上下文视觉识别的适用范围，不复制配置。
COMMENT ON TABLE image_prompt_model_capability IS '平台模型视觉准入，供图像转提示词和上下文图片/PDF页识别共同读取；管理员全局维护，按platform_model公开标识关联，不保存用户材料或密钥';
