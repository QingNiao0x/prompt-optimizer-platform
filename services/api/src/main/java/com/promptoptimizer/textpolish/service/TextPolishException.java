package com.promptoptimizer.textpolish.service;

/**
 * 润色业务可公开的固定错误；不携带正文、上游返回或敏感凭据。
 * @author QingNiao
 * @since 0.1.0
 */
public class TextPolishException extends RuntimeException {
    private final int status;
    private final String code;
    public TextPolishException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    /** 输入问题在模型调用前拒绝。 */
    public static TextPolishException invalid(String message) { return new TextPolishException(400, "TEXT_POLISH_INVALID", message); }
    /** 不区分不存在、删除或越权，避免暴露其他身份的资源。 */
    public static TextPolishException missing() { return new TextPolishException(404, "TEXT_POLISH_NOT_FOUND", "记录不存在、已删除或不在当前可访问范围。"); }
    /** 并发或幂等冲突，客户端保留草稿后显式处理。 */
    public static TextPolishException conflict(String message) { return new TextPolishException(409, "TEXT_POLISH_CONFLICT", message); }
}
