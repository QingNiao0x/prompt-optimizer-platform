package com.promptoptimizer.identity.settings;

/**
 * 系统设置的稳定业务错误；消息和原因码均不得包含账户或验证码秘密。
 * @author QingNiao
 * @since 0.1.0
 */
public class AccountSettingsException extends RuntimeException {
    private final int status;
    private final String code;

    public AccountSettingsException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    /** 只对验证码拒绝提交已增加的尝试数，其余业务异常继续整笔回滚。 */
    public static final class CodeRejected extends AccountSettingsException {
        public CodeRejected(String code, String message) { super(400, code, message); }
    }
}
