package com.promptoptimizer.common.exception;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 服务端未配置 API Key 加密主密钥时抛出，用于返回可操作的配置错误提示。
 */
public class EncryptionSecretMissingException extends IllegalStateException {

    /**
     * 使用固定提示构造异常。
     */
    public EncryptionSecretMissingException() {
        super("API_KEY_ENCRYPTION_SECRET 未配置，无法加密或解密 API Key");
    }
}
