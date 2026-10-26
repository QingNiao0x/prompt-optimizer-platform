-- 第三方昵称属于已绑定的登录身份，不把昵称当作认证键，也不覆盖用户自填的账户显示名。
ALTER TABLE user_identity
    ADD COLUMN provider_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_user_identity_provider_profile_object
        CHECK (jsonb_typeof(provider_profile) = 'object');

COMMENT ON COLUMN user_identity.provider_profile IS
    '关联账户的第三方身份展示资料，JSONB顶层对象，非空，默认{}表示尚未同步。当前已知键：nickname为经授权取得的第三方昵称字符串，nicknameSyncedAt为ISO-8601含时区同步时间字符串；键集合可扩展，数据库仅校验顶层为对象，不强制这些键或其值类型。本地身份保持{}；当前同步入口仅更新已绑定且ACTIVE的WECHAT身份及活动账户，不保存凭据、Token，不参与认证或角色判定。微信授权网络接入尚未开放。';
COMMENT ON COLUMN user_account.display_name IS
    '账户显示昵称，VARCHAR(80)非空，不参与登录身份查找且不要求唯一。注册可选填写，空白/未提供沿用原默认名；第三方昵称独立保存在user_identity.provider_profile，不能静默覆盖自填显示名。';
