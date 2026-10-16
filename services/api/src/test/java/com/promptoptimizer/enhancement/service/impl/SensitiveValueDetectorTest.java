package com.promptoptimizer.enhancement.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueDetectorTest {

    private final SensitiveValueDetector detector = new SensitiveValueDetector();

    @Test
    void shouldDetectCredentialValuesWithoutRejectingSecurityRequirements() {
        assertThat(detector.containsCredential("API Key = sk-realisticSecret123456")).isTrue();
        assertThat(detector.containsCredential("不得在日志中输出 API Key 或 Token")).isFalse();
        assertThat(detector.containsCredential("api_key=your-api-key-placeholder")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"api_key\":\"SyntheticCanary987654\"}",
            "https://example.org/?token=CanaryValue98765",
            "secret=CanaryValue98765",
            "password=Ab9!",
            "https://user:Ab9!@example.org/",
            "api_key=Live_test_Value987654",
            "Authorization: Bearer Live_example_Value987654",
            "https://example.org/?%74oken=CanaryValue98765",
            "{\"access_token\":\"Canary\\u0056alue98765\"}",
            "{\"token\":\"Ab9!\"}", "https://example.org/?token=Ab9!", "Authorization: Basic dTpw"
    })
    void detectsStructuredOrEncodedValuesWithoutSubstringPlaceholderBypass(String input) {
        assertThat(detector.containsCredential(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "api_key=${API_KEY}", "token=<TOKEN>", "api_key=your-api-key-placeholder",
            "tokenCount=100", "不得返回 API Key 或 Token",
            "api_key=System.getenv(\"API_KEY\")", "token=os.getenv(\"TOKEN\")",
            "支持 Bearer token 认证，不得回显凭据"
    })
    void preservesCompletePlaceholdersAndSecurityInstructions(String input) {
        assertThat(detector.containsCredential(input)).isFalse();
    }
}
