-- V5 和 V9 已在现有数据库执行，不修改历史迁移的校验和；集中补充当前有效字段的取值说明。
-- 此迁移只更新数据库元数据，不改变已有数据、约束或索引。
COMMENT ON COLUMN user_identity.identity_type IS '登录身份类型：EMAIL=邮箱登录身份，通过电子邮件地址进行用户认证；PHONE=手机号登录身份，预留短信验证码或密码登录；WECHAT=微信登录身份，预留微信开放平台授权的第三方登录；USERNAME=用户名登录身份，通过自定义用户名进行密码登录。PHONE 和 WECHAT 认证流程当前尚未开放。';
COMMENT ON COLUMN user_identity.status IS '登录身份状态：ACTIVE=有效，可用于认证查找；REVOKED=已撤销绑定，不可再用于登录。';
COMMENT ON COLUMN user_identity.issuer IS '身份签发方：EMAIL、PHONE 和 USERNAME 的本地身份使用 local；WECHAT 预留对应微信开放平台应用标识。';
COMMENT ON COLUMN user_account.email IS '账户联系邮箱，兼容现有资料展示；实际登录身份及唯一性由 user_identity 管理。';
COMMENT ON COLUMN user_account.status IS '账户状态：ACTIVE=启用，可正常登录；LOCKED=暂时锁定；DISABLED=禁用，不可登录。';
COMMENT ON COLUMN user_account.platform_role IS '平台级角色：USER=普通平台用户；PLATFORM_ADMIN=平台管理员，可维护平台模型目录；与工作区成员角色分别授权。默认 USER。';
COMMENT ON COLUMN tenant.tenant_type IS '租户类型：PERSONAL=个人租户；TEAM=团队租户。默认 PERSONAL。';
COMMENT ON COLUMN tenant.status IS '租户状态：ACTIVE=启用；SUSPENDED=暂停使用；DELETED=已删除，业务读取应过滤。默认 ACTIVE。';
COMMENT ON COLUMN workspace.status IS '工作区状态：ACTIVE=可用；ARCHIVED=已归档；DELETED=已删除，业务读取应过滤。默认 ACTIVE。';
COMMENT ON COLUMN workspace_member.role IS '工作区成员角色：OWNER=所有者，可管理工作区；EDITOR=编辑者；VIEWER=查看者。具体操作仍以接口授权规则为准。';
COMMENT ON COLUMN optimization_session.status IS '优化会话状态：ACTIVE=可继续使用；ARCHIVED=已归档。默认 ACTIVE。';
COMMENT ON COLUMN optimization_record.template_code IS '本次优化使用的模板编码：GENERAL=通用任务；RESEARCH_ANALYSIS=科研分析；FEATURE_DEVELOPMENT=功能开发；BUG_FIX=缺陷修复；REFACTORING=代码重构；TESTING=测试任务。请求中的 AUTO 表示自动选择，实际记录以最终选中的编码为准。';
COMMENT ON COLUMN usage_event.usage_status IS '模型调用用量状态：COMPLETED=调用完成且用量数据完整；FAILED=调用失败；INCOMPLETE=调用已记录但用量数据不完整。';
