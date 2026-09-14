package com.promptoptimizer.enhancement.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueDetectorTest {

    private final SensitiveValueDetector detector = new SensitiveValueDetector();

    @Test
    void shouldDetectCredentialValuesWithoutRejectingSecurityRequirements() {
        assertThat(detector.containsCredential("API Key = sk-realisticSecret123456")).isTrue();
        assertThat(detector.containsCredential("不得在日志中输出 API Key 或 Token")).isFalse();
        assertThat(detector.containsCredential("api_key=your-api-key-placeholder")).isFalse();
    }
}
