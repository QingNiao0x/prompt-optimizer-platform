package com.promptoptimizer.enhancement.application;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检测即将发送或返回的文本中是否包含疑似真实凭据值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class SensitiveValueDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:api[ _-]?key|access[ _-]?token|client[ _-]?secret|password)\\b\\s*[:=]\\s*[\\\"']?([^\\s\\\"',;]{8,})"),
            Pattern.compile("(?i)\\b(?:bearer|basic)\\s+([^\\s,;]{12,})"),
            Pattern.compile("\\b(sk-[A-Za-z0-9_-]{10,})\\b"),
            Pattern.compile("(-----BEGIN [A-Z ]*PRIVATE KEY-----)")
    );
    private static final List<String> PLACEHOLDERS = List.of(
            "placeholder", "replace", "example", "your-", "your_", "test-", "test_", "redacted", "xxxx"
    );

    /**
     * 检测疑似真实凭据；常见示例占位符不视为泄露，降低误报。
     */
    public boolean containsCredential(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                String candidate = matcher.group(1).toLowerCase(Locale.ROOT);
                if (PLACEHOLDERS.stream().noneMatch(candidate::contains)) {
                    return true;
                }
            }
        }
        return false;
    }
}
