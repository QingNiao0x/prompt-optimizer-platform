package com.promptoptimizer.settings.application;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加密工具，使用 AES-256-GCM 对密钥做静态加密。
 *
 * <p>密文格式为 Base64(随机 IV + 密文)，每次加密使用新的随机 IV，
 * 因此相同明文不会产生相同密文。主密钥通过 SHA-256 派生，要求由运行环境注入。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ApiKeyCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec secretKey;

    /**
     * 使用运行环境注入的加密主密钥派生 AES 密钥。
     */
    public ApiKeyCipher(String encryptionSecret) {
        if (encryptionSecret == null || encryptionSecret.isBlank()) {
            throw new IllegalStateException("API_KEY_ENCRYPTION_SECRET 未配置，无法加密或解密 API Key");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(encryptionSecret.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化 API Key 加密密钥", exception);
        }
    }

    /**
     * 加密明文 API Key，返回 Base64(IV + 密文)。
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("API Key 加密失败", exception);
        }
    }

    /**
     * 解密 Base64(IV + 密文)，仅在需要调用真实模型时使用。
     */
    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("API Key 密文格式无效");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("API Key 解密失败，请检查加密主密钥是否变更", exception);
        }
    }

    /**
     * 返回 API Key 末四位，用于界面确认，不返回完整明文。
     */
    public String last4(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        String value = plaintext.trim();
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
