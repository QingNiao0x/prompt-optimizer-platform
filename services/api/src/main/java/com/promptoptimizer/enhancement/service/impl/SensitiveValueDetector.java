package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.common.security.SensitiveValueScanner;

/**
 * 检测即将发送或返回的文本中是否包含疑似真实凭据值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class SensitiveValueDetector {

    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    /**
     * 检测疑似真实凭据；常见示例占位符不视为泄露，降低误报。
     */
    public boolean containsCredential(String value) {
        return scanner.containsCredential(value);
    }
}
