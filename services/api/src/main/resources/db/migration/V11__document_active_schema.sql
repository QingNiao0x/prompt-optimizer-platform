-- Document the active schema without rewriting migrations that have already been applied.
-- provider_config was retired in V7 and is intentionally absent from this migration.

COMMENT ON TABLE tenant IS '租户隔离主体，表示个人账户或团队组织，并作为账户、工作区和计费数据的归属边界。';
COMMENT ON COLUMN tenant.id IS '主键：租户稳定标识；被 user_account、workspace、usage_event 和 audit_event 引用。';
COMMENT ON COLUMN tenant.name IS '租户展示名称，不作为权限判断或唯一身份标识。';
COMMENT ON COLUMN tenant.tenant_type IS '租户类型：PERSONAL=个人租户；TEAM=团队租户。数据库 CHECK 仅允许这两个值，默认 PERSONAL。';
COMMENT ON COLUMN tenant.plan_code IS '套餐编码；当前默认 FREE，MVP 阶段没有 CHECK 枚举约束，不能据此推断可用套餐范围。';
COMMENT ON COLUMN tenant.status IS '租户状态：ACTIVE=启用；SUSPENDED=暂停；DELETED=已删除。数据库 CHECK 仅允许这三个值，默认 ACTIVE。';
COMMENT ON COLUMN tenant.created_at IS '租户创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN tenant.updated_at IS '租户最后更新时间，带时区；写入和更新时由数据库默认值或应用维护。';

COMMENT ON TABLE user_account IS '平台用户账户主表，保存稳定用户标识、联系资料、密码哈希和平台角色；登录身份另存于 user_identity。';
COMMENT ON COLUMN user_account.id IS '主键：平台用户稳定标识；作为身份、工作区成员、会话和审计记录的账户关联键。';
COMMENT ON COLUMN user_account.tenant_id IS '所属租户标识，外键引用 tenant.id；用于租户数据隔离。';
COMMENT ON COLUMN user_account.email IS '可空的联系邮箱资料；邮箱登录唯一性由 user_identity 管理，此字段为空时账户仍可使用用户名登录。';
COMMENT ON COLUMN user_account.display_name IS '用户展示名称；不参与登录身份查找。';
COMMENT ON COLUMN user_account.password_hash IS '密码单向哈希，不得保存明文；NULL 表示尚未配置本地密码，当前由 BCrypt 编码。';
COMMENT ON COLUMN user_account.status IS '账户状态：ACTIVE=允许登录；LOCKED=锁定；DISABLED=禁用。数据库 CHECK 仅允许这三个值，默认 ACTIVE。';
COMMENT ON COLUMN user_account.last_login_at IS '最近一次成功登录时间；未登录时为空。';
COMMENT ON COLUMN user_account.created_at IS '账户创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN user_account.updated_at IS '账户最后更新时间，带时区。';
COMMENT ON COLUMN user_account.platform_role IS '平台级角色：USER=普通用户；PLATFORM_ADMIN=平台管理员。数据库 CHECK 仅允许这两个值，默认 USER；与工作区 role 独立。';

COMMENT ON TABLE workspace IS '租户内的协作和提示词工作区，是项目上下文、优化历史及成员关系的主要数据范围。';
COMMENT ON COLUMN workspace.id IS '主键：工作区稳定标识；被成员、会话、优化记录和用量事件引用。';
COMMENT ON COLUMN workspace.tenant_id IS '所属租户标识，外键引用 tenant.id；工作区不得跨租户归属。';
COMMENT ON COLUMN workspace.name IS '工作区展示名称。';
COMMENT ON COLUMN workspace.description IS '工作区说明，可为空；用于补充工作区业务背景。';
COMMENT ON COLUMN workspace.preferences IS '工作区偏好 JSON 对象，默认 {}；预留工作区级设置，目前没有由数据库约束的固定键结构。';
COMMENT ON COLUMN workspace.created_by IS '创建用户标识，外键引用 user_account.id；记录工作区发起人。';
COMMENT ON COLUMN workspace.status IS '工作区状态：ACTIVE=可用；ARCHIVED=已归档；DELETED=已删除。数据库 CHECK 仅允许这三个值，默认 ACTIVE。';
COMMENT ON COLUMN workspace.created_at IS '工作区创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN workspace.updated_at IS '工作区最后更新时间，带时区。';

COMMENT ON TABLE workspace_member IS '工作区成员关系表，以工作区和用户的组合主键记录成员资格及工作区内角色。';
COMMENT ON COLUMN workspace_member.workspace_id IS '工作区标识，外键引用 workspace.id；删除工作区时数据库级联删除此关系。';
COMMENT ON COLUMN workspace_member.user_id IS '用户标识，外键引用 user_account.id；删除账户时数据库级联删除此关系。';
COMMENT ON COLUMN workspace_member.role IS '工作区角色：OWNER=所有者；EDITOR=编辑者；VIEWER=查看者。数据库 CHECK 仅允许这三个值；平台管理员权限由 user_account.platform_role 单独控制。';
COMMENT ON COLUMN workspace_member.joined_at IS '成员加入工作区的时间，默认由数据库写入当前时间。';
COMMENT ON COLUMN workspace_member.updated_at IS '成员角色或关系最后更新时间。';

COMMENT ON TABLE optimization_session IS '优化历史会话表，在同一租户和工作区范围内组织一组相关的提示词优化记录。';
COMMENT ON COLUMN optimization_session.id IS '主键：优化会话稳定标识；被 optimization_record.session_id 引用。';
COMMENT ON COLUMN optimization_session.tenant_id IS '所属租户标识，外键引用 tenant.id；用于租户隔离。';
COMMENT ON COLUMN optimization_session.workspace_id IS '所属工作区标识，外键引用 workspace.id；会话的历史记录必须处于该工作区范围。';
COMMENT ON COLUMN optimization_session.created_by IS '创建用户标识，外键引用 user_account.id。';
COMMENT ON COLUMN optimization_session.title IS '会话展示标题，可为空。';
COMMENT ON COLUMN optimization_session.status IS '会话状态：ACTIVE=可继续使用；ARCHIVED=已归档。数据库 CHECK 仅允许这两个值，默认 ACTIVE。';
COMMENT ON COLUMN optimization_session.created_at IS '会话创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN optimization_session.updated_at IS '会话最后更新时间，带时区。';

COMMENT ON TABLE optimization_record IS '用户保存的提示词优化历史，按租户、工作区、会话和创建者追溯；文件正文不持久化，上下文仅保存脱敏摘要。';
COMMENT ON COLUMN optimization_record.id IS '主键：单次优化历史记录稳定标识。';
COMMENT ON COLUMN optimization_record.tenant_id IS '所属租户标识，外键引用 tenant.id；查询必须同时执行租户范围校验。';
COMMENT ON COLUMN optimization_record.workspace_id IS '所属工作区标识，外键引用 workspace.id；查询必须同时执行工作区范围校验。';
COMMENT ON COLUMN optimization_record.session_id IS '所属优化会话标识，外键引用 optimization_session.id。';
COMMENT ON COLUMN optimization_record.created_by IS '发起优化的用户标识，外键引用 user_account.id。';
COMMENT ON COLUMN optimization_record.template_code IS '最终采用的模板编码：GENERAL=通用；RESEARCH_ANALYSIS=科研分析；FEATURE_DEVELOPMENT=功能开发；BUG_FIX=缺陷修复；REFACTORING=重构；TESTING=测试。AUTO 仅为请求侧自动选择指令，不应作为最终采用模板保存；此列当前无 CHECK 约束。';
COMMENT ON COLUMN optimization_record.raw_prompt IS '本次增强的原始提示词；仅用户保存历史时持久化，属于用户业务内容。';
COMMENT ON COLUMN optimization_record.optimized_prompt IS '模型生成并保存的最终结构化提示词。';
COMMENT ON COLUMN optimization_record.context_snapshot IS '脱敏且不含文件正文的上下文 JSON 对象；当前键包括 customDescription、technologyStack、dependencies、directoryTree、warnings、redactions、analysisVersion。';
COMMENT ON COLUMN optimization_record.result_metadata IS '优化结果 JSON 对象；包含 sections、ambiguities、appliedConstraints、provider、model、mock、enhancementOptions、conversationHistory，以及可选 planConfirmation。';
COMMENT ON COLUMN optimization_record.permission_policy IS '权限策略 JSON 对象；包含 protectedPaths 字符串数组和 requireConfirmationFor 人工确认动作数组。';
COMMENT ON COLUMN optimization_record.latency_ms IS '本次优化耗时，单位毫秒；NULL 表示未记录，CHECK 要求非 NULL 时大于或等于零。';
COMMENT ON COLUMN optimization_record.retention_until IS '本条历史记录的保留截止时间，带时区；NULL 表示未设置自动保留截止时间。';
COMMENT ON COLUMN optimization_record.created_at IS '历史记录创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN optimization_record.deleted_at IS '逻辑删除时间，带时区；NULL 表示可见，非 NULL 表示已删除并应从常规读取中排除。';

COMMENT ON TABLE usage_event IS '模型调用用量事实表，按租户记录模型路由、Token、耗时、估算成本和调用结果，供排障与计量使用。';
COMMENT ON COLUMN usage_event.id IS '主键：单次模型调用用量事件标识。';
COMMENT ON COLUMN usage_event.tenant_id IS '计费用租户标识，外键引用 tenant.id。';
COMMENT ON COLUMN usage_event.workspace_id IS '所属工作区标识，外键引用 workspace.id；系统级或无法归属工作区的事件可为空。';
COMMENT ON COLUMN usage_event.optimization_record_id IS '关联的提示词优化记录标识，外键引用 optimization_record.id；失败或尚未保存历史的调用可为空。';
COMMENT ON COLUMN usage_event.request_id IS '平台请求标识，用于关联 API 日志和模型调用日志；不得写入密钥或用户正文。';
COMMENT ON COLUMN usage_event.provider_type IS '实际模型供应商或服务端路由标识；当前为普通字符串，不受数据库 CHECK 枚举约束。';
COMMENT ON COLUMN usage_event.model_name IS '实际调用的上游模型名称，不是用户提交的密钥或端点。';
COMMENT ON COLUMN usage_event.input_tokens IS '上游报告的输入 Token 数；未知时为空，数据库 CHECK 禁止负数。';
COMMENT ON COLUMN usage_event.output_tokens IS '上游报告的输出 Token 数；未知时为空，数据库 CHECK 禁止负数。';
COMMENT ON COLUMN usage_event.latency_ms IS '模型 Provider 调用耗时，单位毫秒；未知时为空。';
COMMENT ON COLUMN usage_event.estimated_cost IS '按平台费率估算的调用成本，不代表供应商最终账单金额。';
COMMENT ON COLUMN usage_event.currency IS '估算成本的 ISO 4217 三字符货币代码，例如 USD；无成本估算时为空。';
COMMENT ON COLUMN usage_event.usage_status IS '用量状态：COMPLETED=调用成功且用量数据完整；FAILED=调用失败；INCOMPLETE=调用已记录但用量数据不完整。数据库 CHECK 仅允许这三个值。';
COMMENT ON COLUMN usage_event.metadata IS '非敏感供应商用量扩展 JSON 对象；默认 {}，不得包含 API Key、密码、Token 凭据或完整请求/响应正文。';
COMMENT ON COLUMN usage_event.occurred_at IS '模型调用事件发生时间，带时区；默认由数据库写入当前时间。';

COMMENT ON TABLE audit_event IS '平台管理与安全审计事件表，按租户记录操作者、事件类型和脱敏变更摘要，不存储凭据或敏感正文。';
COMMENT ON COLUMN audit_event.id IS '主键：单条审计事件稳定标识。';
COMMENT ON COLUMN audit_event.tenant_id IS '审计事件所属租户标识，外键引用 tenant.id。';
COMMENT ON COLUMN audit_event.actor_user_id IS '执行操作的用户标识，外键引用 user_account.id；系统任务或无法归属用户的动作可为空。';
COMMENT ON COLUMN audit_event.event_type IS '审计事件代码，由产生事件的业务模块定义；当前未设置数据库 CHECK 枚举。';
COMMENT ON COLUMN audit_event.resource_type IS '被操作资源类型，例如 USER_ACCOUNT 或 PLATFORM_MODEL；可为空。';
COMMENT ON COLUMN audit_event.resource_id IS '被操作资源标识；可为空，且不设置通用外键以容纳不同资源表。';
COMMENT ON COLUMN audit_event.details IS '脱敏后的审计 JSON 对象，存放与事件类型对应的变更摘要；默认 {}，不得包含密码、密钥、Token 或源码正文。';
COMMENT ON COLUMN audit_event.occurred_at IS '审计事件发生时间，带时区；默认由数据库写入当前时间。';

COMMENT ON TABLE user_identity IS '登录身份表，将邮箱、手机号、微信或用户名等认证标识与稳定平台账户关联；一个账户可拥有多个不同身份。';
COMMENT ON COLUMN user_identity.id IS '主键：登录身份绑定关系稳定标识。';
COMMENT ON COLUMN user_identity.user_id IS '身份所属账户标识，外键引用 user_account.id；删除账户时身份记录级联删除。';
COMMENT ON COLUMN user_identity.identity_type IS '身份类型：EMAIL=邮箱登录；PHONE=手机号登录；WECHAT=微信开放平台登录；USERNAME=用户名密码登录。数据库 CHECK 仅允许这四个值；PHONE 和 WECHAT 的认证流程当前尚未开放。';
COMMENT ON COLUMN user_identity.issuer IS '身份签发方；本地 EMAIL、PHONE、USERNAME 使用 local，WECHAT 使用对应开放平台应用标识。';
COMMENT ON COLUMN user_identity.identifier IS '原始身份标识；仅保存完成登录查找所需的邮箱、用户名或第三方主体标识，不保存访问令牌。';
COMMENT ON COLUMN user_identity.normalized_identifier IS '用于唯一索引和认证查询的规范化身份标识；邮箱与用户名转为小写，用户名需符合 3 至 32 位 ASCII 格式约束。';
COMMENT ON COLUMN user_identity.status IS '身份状态：ACTIVE=可用于认证；REVOKED=已撤销且不可登录。数据库 CHECK 仅允许这两个值，默认 ACTIVE。';
COMMENT ON COLUMN user_identity.verified_at IS '该身份完成邮箱、短信或第三方验证的时间；本地用户名可为空，历史迁移身份也可为空。';
COMMENT ON COLUMN user_identity.last_used_at IS '该身份最近一次成功用于认证的时间；尚未使用时为空。';
COMMENT ON COLUMN user_identity.created_at IS '身份绑定创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN user_identity.updated_at IS '身份绑定最后更新时间，带时区；更新身份状态或绑定关系时刷新。';

COMMENT ON TABLE platform_model IS '平台管理员维护的用户可选模型目录；只保存展示信息和服务端路由键，不保存上游端点或 API Key。';
COMMENT ON COLUMN platform_model.id IS '主键：平台模型目录条目标识。';
COMMENT ON COLUMN platform_model.public_id IS '面向前端的稳定模型标识；客户端提交该值，服务端解析为受控路由和上游模型。';
COMMENT ON COLUMN platform_model.route_key IS '服务端配置的 Provider 路由键；不是请求 URL，也不包含密钥。';
COMMENT ON COLUMN platform_model.upstream_model IS '该路由实际调用的上游模型名称。';
COMMENT ON COLUMN platform_model.display_name IS '用户界面显示的模型名称。';
COMMENT ON COLUMN platform_model.enabled IS '是否允许终端用户选择和调用该目录项，默认 TRUE。';
COMMENT ON COLUMN platform_model.default_model IS '是否为平台默认模型；部分唯一索引保证未删除记录中最多一个默认项，默认 FALSE。';
COMMENT ON COLUMN platform_model.sort_order IS '模型列表排序序号；数据库 CHECK 要求大于或等于零，默认零。';
COMMENT ON COLUMN platform_model.version IS '目录项并发更新版本号；每次变更递增，默认零。';
COMMENT ON COLUMN platform_model.created_at IS '目录项创建时间，带时区；默认由数据库写入当前时间。';
COMMENT ON COLUMN platform_model.updated_at IS '目录项最后更新时间，带时区。';
COMMENT ON COLUMN platform_model.deleted_at IS '逻辑删除时间，带时区；NULL 表示未删除，非 NULL 时从用户可选目录中隐藏。';

COMMENT ON TABLE platform_admin_bootstrap IS '平台管理员一次性初始化控制表；使用唯一单例行防止并发或重复启动重复授予初始管理员权限。';
COMMENT ON COLUMN platform_admin_bootstrap.singleton_id IS '单例行主键；CHECK 仅允许值 1。';
COMMENT ON COLUMN platform_admin_bootstrap.consumed IS '一次性初始化是否已执行；TRUE 后初始化器不再创建账户或重置权限，默认 FALSE。';
COMMENT ON COLUMN platform_admin_bootstrap.consumed_at IS '初始化标记被消费的时间，带时区；未执行时为空。';

COMMENT ON TABLE flyway_schema_history IS 'Flyway 数据库迁移历史表，记录迁移版本、脚本校验和、执行时间与成功状态；由 Flyway 管理。';
COMMENT ON COLUMN flyway_schema_history.installed_rank IS '迁移执行顺序编号。';
COMMENT ON COLUMN flyway_schema_history.version IS '迁移版本号；基准迁移等场景可为空。';
COMMENT ON COLUMN flyway_schema_history.description IS '迁移说明。';
COMMENT ON COLUMN flyway_schema_history.type IS '迁移类型，例如 SQL。';
COMMENT ON COLUMN flyway_schema_history.script IS '实际执行的迁移脚本名称。';
COMMENT ON COLUMN flyway_schema_history.checksum IS '迁移脚本校验和；Flyway 使用它检测已执行脚本是否被修改。';
COMMENT ON COLUMN flyway_schema_history.installed_by IS '执行迁移的数据库账户名称。';
COMMENT ON COLUMN flyway_schema_history.installed_on IS '迁移记录写入时间。';
COMMENT ON COLUMN flyway_schema_history.execution_time IS '迁移执行耗时，单位毫秒。';
COMMENT ON COLUMN flyway_schema_history.success IS '迁移是否成功。';
