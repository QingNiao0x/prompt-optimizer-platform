package com.promptoptimizer.trial;

/**
 * 访客试用的稳定失败契约；不携带请求正文、Cookie 或供应商错误。
 * @author QingNiao
 * @since 0.1.0
 */
public final class TrialException extends RuntimeException {
    private final int status;
    private final String code;
    public TrialException(int status, String code, String message) {
        this(status, code, message, null);
    }
    /** 保留内部根因供受控诊断；统一响应仅使用本类固定安全文案，不输出cause正文。 */
    public TrialException(int status, String code, String message, Throwable cause) {
        super(message, cause); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    /** 存储异常时拒绝新增试用，不回退为本机无限计数。 */
    public static TrialException unavailable() {
        return unavailable(null);
    }
    /** 保留底层异常链但禁止将Redis或序列化内容拼入公开错误。 */
    public static TrialException unavailable(Throwable cause) {
        return new TrialException(503, "TRIAL_UNAVAILABLE", "免费体验暂不可用，请稍后重试或注册登录。", cause);
    }
}
