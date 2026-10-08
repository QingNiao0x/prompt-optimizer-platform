-- 短信授权与业务写入同库提交；云调用和 Redis 限流不参加此事务。
CREATE TABLE sms_verification_challenge (
    id UUID PRIMARY KEY,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('REGISTER', 'LOGIN', 'BIND')),
    phone_fingerprint VARCHAR(64) NOT NULL,
    browser_fingerprint VARCHAR(64) NOT NULL,
    actor_user_id UUID REFERENCES user_account(id) ON DELETE RESTRICT,
    scheme_name VARCHAR(20) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'SENDING'
        CHECK (state IN ('SENDING', 'SENT', 'VERIFYING', 'VERIFIED', 'CONSUMED', 'FAILED', 'SUPERSEDED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    verification_token UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    result_user_id UUID REFERENCES user_account(id) ON DELETE RESTRICT,
    result_code VARCHAR(48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((purpose = 'BIND' AND actor_user_id IS NOT NULL) OR (purpose <> 'BIND' AND actor_user_id IS NULL))
);
CREATE INDEX ix_sms_challenge_subject ON sms_verification_challenge(phone_fingerprint, purpose);
CREATE INDEX ix_sms_challenge_expiry ON sms_verification_challenge(expires_at);

COMMENT ON TABLE sms_verification_challenge IS '短时短信验证及一次性消费结果；按用途、号码HMAC、浏览器HMAC及绑定操作者隔离，不保存短信验证码或明文手机号；非业务删除对象，过期行由独立保留期维护';
COMMENT ON COLUMN sms_verification_challenge.id IS '随机挑战UUID，同时作为幂等操作标识，不能单独作为认证凭据';
COMMENT ON COLUMN sms_verification_challenge.purpose IS '用途：REGISTER手机号注册、LOGIN普通用户短信登录、BIND首次绑定手机号；无默认值，不支持换绑或重置密码';
COMMENT ON COLUMN sms_verification_challenge.phone_fingerprint IS '规范化E.164手机号的HMAC-SHA256十六进制指纹；使用独立服务端业务密钥';
COMMENT ON COLUMN sms_verification_challenge.browser_fingerprint IS '服务端会话内随机浏览器绑定值的HMAC-SHA256指纹，不保存Cookie或会话ID';
COMMENT ON COLUMN sms_verification_challenge.actor_user_id IS '绑定操作者，引用user_account.id；仅BIND非空，来自服务端CurrentActor，删除受限';
COMMENT ON COLUMN sms_verification_challenge.scheme_name IS '云端发送和核验共用的环境及用途隔离方案名，最多20字符，不包含个人信息';
COMMENT ON COLUMN sms_verification_challenge.state IS '状态：SENDING待提交、SENT已受理、VERIFYING核验占用、VERIFIED云端通过、CONSUMED已处理、FAILED终止、SUPERSEDED被重发替换；默认SENDING；过期以expires_at判断';
COMMENT ON COLUMN sms_verification_challenge.attempts IS '已预留的云端核验尝试次数，含结果不确定的尝试，默认0，最多5次，不因重试或失败清零';
COMMENT ON COLUMN sms_verification_challenge.verification_token IS '当前云核验请求的栅栏UUID；无在途核验时为空，防止迟到响应恢复失效挑战';
COMMENT ON COLUMN sms_verification_challenge.expires_at IS '授权失效时刻，带时区，按UTC比较；验证通过不延长原5分钟有效期';
COMMENT ON COLUMN sms_verification_challenge.result_user_id IS '已完成操作的账户，引用user_account.id；尚未成功或冲突时为空，不能据此重建已消费的短信登录会话';
COMMENT ON COLUMN sms_verification_challenge.result_code IS '最终安全结果代码，由应用维护，无数据库固定枚举约束；未完成时为空，不包含供应商响应或个人信息';
COMMENT ON COLUMN sms_verification_challenge.created_at IS '挑战创建时刻，带时区，默认数据库当前时间';

-- 保留跨账户全局身份唯一性，同时约束首期每账户仅一个有效手机身份。
CREATE UNIQUE INDEX uq_user_identity_active_phone_user ON user_identity(user_id)
    WHERE identity_type = 'PHONE' AND status = 'ACTIVE';
ALTER TABLE user_identity ADD CONSTRAINT ck_user_identity_phone_local
    CHECK (identity_type <> 'PHONE' OR (issuer = 'local' AND normalized_identifier ~ '^\+[1-9][0-9]{7,14}$'
        AND identifier = normalized_identifier));
COMMENT ON COLUMN user_identity.user_id IS '身份所属账户，引用user_account.id；同一身份只属于一个账户，每账户至多一个ACTIVE手机号身份，不允许绑定接口转移所有权';
COMMENT ON COLUMN user_identity.identity_type IS '身份类型：EMAIL邮箱、PHONE手机号、WECHAT微信（预留未开放）、USERNAME用户名；无默认值；手机号注册/短信登录/绑定由后端开关控制，管理员不能仅凭短信登录';
COMMENT ON COLUMN user_identity.issuer IS '身份签发方；PHONE由CHECK强制为local，不使用短信供应商名称；其他本地身份由应用固定local，微信使用应用标识';
COMMENT ON COLUMN user_identity.identifier IS '标准展示标识，属于个人信息，不记录在日志；PHONE由CHECK要求与E.164规范化标识一致';
COMMENT ON COLUMN user_identity.normalized_identifier IS '身份唯一键标识；手机号由CHECK要求E.164，公开国内短信入口额外只允许+86；EMAIL和USERNAME由应用去首尾空白并转小写；不保存验证码或Token';
