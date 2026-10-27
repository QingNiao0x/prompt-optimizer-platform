ALTER TABLE user_account
    ADD COLUMN locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN',
    ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_user_account_locale CHECK (locale IN ('zh-CN', 'en')),
    ADD CONSTRAINT ck_user_account_security_version CHECK (security_version >= 0),
    ADD CONSTRAINT ck_user_account_deleted_disabled CHECK (deleted_at IS NULL OR status = 'DISABLED');
COMMENT ON COLUMN user_account.locale IS '账户界面语言：zh-CN=简体中文（默认），en=英文；由CHECK约束，跨设备持久化。';
COMMENT ON COLUMN user_account.security_version IS '全设备会话撤销版本，非负整数、默认0；安全操作递增，服务端每请求与登录快照比较。';
COMMENT ON COLUMN user_account.deleted_at IS '账户逻辑注销时间，TIMESTAMPTZ绝对时刻；NULL=未注销，非NULL时必须DISABLED；保留租户、身份和业务历史关联。';
COMMENT ON COLUMN user_account.status IS '账户状态：ACTIVE=可认证，LOCKED=锁定，DISABLED=禁用或逻辑注销；默认ACTIVE，已注销账户不得恢复为ACTIVE。';

CREATE TABLE email_binding_challenge (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id),
    email_fingerprint CHAR(64) NOT NULL,
    browser_fingerprint CHAR(64) NOT NULL,
    code_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_email_binding_challenge_status CHECK(status IN ('PENDING','SENT','SUPERSEDED','CONSUMED','FAILED')),
    CONSTRAINT ck_email_binding_challenge_attempts CHECK(attempts BETWEEN 0 AND 5)
);
CREATE INDEX ix_email_binding_challenge_user ON email_binding_challenge(user_id, created_at);
COMMENT ON TABLE email_binding_challenge IS '账户设置邮箱绑定专用一次性挑战；归属于user_account，不用于注册或登录；仅存HMAC指纹，无明文邮箱或验证码，保留消费状态防重放。';
COMMENT ON COLUMN email_binding_challenge.id IS '服务端随机挑战UUID，客户端提交时仍须验证账户、浏览器和目标邮箱。';
COMMENT ON COLUMN email_binding_challenge.user_id IS '引用user_account.id；仅该当前登录账户可核验消费，不改变租户或历史归属。';
COMMENT ON COLUMN email_binding_challenge.email_fingerprint IS '规范化目标邮箱的用途隔离HMAC-SHA256十六进制指纹，固定64字符。';
COMMENT ON COLUMN email_binding_challenge.browser_fingerprint IS '服务端会话内随机浏览器绑定值的HMAC-SHA256指纹，固定64字符。';
COMMENT ON COLUMN email_binding_challenge.code_digest IS '挑战ID、账户、目标及六位验证码的HMAC-SHA256摘要，固定64字符，不可用于其他挑战。';
COMMENT ON COLUMN email_binding_challenge.status IS 'PENDING=待投递（默认），SENT=可核验，SUPERSEDED=被新挑战替换，CONSUMED=已消费，FAILED=投递失败或尝试耗尽；CHECK限制值域。';
COMMENT ON COLUMN email_binding_challenge.attempts IS '错误核验次数，默认0，范围0至5；第5次错误使挑战失效。';
COMMENT ON COLUMN email_binding_challenge.expires_at IS '挑战过期绝对时刻（TIMESTAMPTZ），创建后5分钟；发送耗时不延长有效期。';
COMMENT ON COLUMN email_binding_challenge.created_at IS '数据库创建绝对时刻（TIMESTAMPTZ），默认CURRENT_TIMESTAMP。';
COMMENT ON COLUMN email_binding_challenge.consumed_at IS '同身份写入事务中的消费绝对时刻（TIMESTAMPTZ）；未消费为NULL。';

CREATE TABLE email_binding_rate_limit (
    budget_key CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    sends INTEGER NOT NULL,
    next_allowed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_email_binding_rate_limit_sends CHECK(sends > 0)
);
COMMENT ON TABLE email_binding_rate_limit IS '邮箱绑定发码的数据库原子预算；按账户、目标邮箱和可信连接来源IP分别HMAC隔离，跨实例一致，供应商失败不退还预算。';
COMMENT ON COLUMN email_binding_rate_limit.budget_key IS '含预算类别的HMAC-SHA256十六进制指纹；不存明文邮箱、IP或浏览器信息。';
COMMENT ON COLUMN email_binding_rate_limit.window_started_at IS '当前滚动小时窗口起始绝对时刻（TIMESTAMPTZ），窗口过期后下次发送重置。';
COMMENT ON COLUMN email_binding_rate_limit.sends IS '窗口内已预留发送次数，正整数；账户和邮箱默认上限5次，IP默认上限20次，投递失败仍计数。';
COMMENT ON COLUMN email_binding_rate_limit.next_allowed_at IS '下一次允许预留的绝对时刻（TIMESTAMPTZ）；账户与邮箱有60秒冷却，IP只设小时预算。';
