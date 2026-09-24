package com.promptoptimizer.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提供适用于单行键值日志的受限字段格式化，避免配置标签造成换行注入或超长记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class LogFields {

    private static final List<Pattern> CREDENTIAL_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:api[ _-]?key|access[ _-]?token|client[ _-]?secret|password)\\b\\s*[:=]\\s*[^\\s,;]{8,}"),
            Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[^\\s,;]{12,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{10,}\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    );

    private LogFields() {
    }

    /** 将字段规整为单行且有长度上限的标签，避免换行注入和异常长配置值。 */
    public static String value(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String bounded = value.length() > 512 ? value.substring(0, 512) : value;
        if (containsCredential(bounded)) {
            return "[REDACTED]";
        }
        String normalized = bounded.strip().replaceAll("[\\p{Cntrl}\\s=]+", "_");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    /** 检查仅会写入日志标签的受限字段是否呈现常见凭据格式。 */
    private static boolean containsCredential(String value) {
        return CREDENTIAL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }
}
