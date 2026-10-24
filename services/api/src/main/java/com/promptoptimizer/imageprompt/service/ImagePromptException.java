package com.promptoptimizer.imageprompt.service;

/**
 * 仅携带可公开的固定业务错误，不附带图片、模型响应、原始输入或凭据。
 * @author QingNiao
 * @since 0.1.0
 */
public class ImagePromptException extends RuntimeException {
    private final int status;
    private final String code;
    public ImagePromptException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ImagePromptException invalid(String message) { return new ImagePromptException(400, "IMAGE_PROMPT_INVALID", message); }
    public static ImagePromptException missing() { return new ImagePromptException(404, "IMAGE_PROMPT_NOT_FOUND", "记录不存在、已删除或不在当前可访问范围。"); }
    public static ImagePromptException expired() { return new ImagePromptException(409, "IMAGE_PROMPT_EXPIRED", "图片或计划已过期，请重新选择图片或梳理创作意图。"); }
    public static ImagePromptException conflict(String message) { return new ImagePromptException(409, "IMAGE_PROMPT_CONFLICT", message); }
}
