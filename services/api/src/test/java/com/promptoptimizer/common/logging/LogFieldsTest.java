package com.promptoptimizer.common.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证日志标签的凭据脱敏、换行处理和长度上限。 */
class LogFieldsTest {

    @Test
    void shouldRedactCommonCredentialFormats() {
        assertThat(LogFields.value("api_key=super-secret-value-for-tests")).isEqualTo("[REDACTED]");
        assertThat(LogFields.value("Bearer eyJhbGciOiJIUzI1NiJ9.signature-value")).isEqualTo("[REDACTED]");
        assertThat(LogFields.value("sk-0123456789abcdefghij")).isEqualTo("[REDACTED]");
        assertThat(LogFields.value("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void shouldMakeLabelsSingleLineAndBoundTheirLength() {
        assertThat(LogFields.value("provider\nname=value")).isEqualTo("provider_name_value");
        assertThat(LogFields.value("x".repeat(200))).hasSize(120);
    }
}
