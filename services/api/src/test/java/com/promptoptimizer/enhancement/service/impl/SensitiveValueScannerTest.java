package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueScannerTest {
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    @Test
    void redactsOriginalEncodedSpansAndPreservesJsonAndBusinessText() throws Exception {
        String input = "{\"purpose\":\"保留业务目标\",\"token\":\"Canary\\u0056alue98765\"}";
        String safe = scanner.redact(input);
        assertThat(new ObjectMapper().readTree(safe).path("purpose").asText()).isEqualTo("保留业务目标");
        assertThat(new ObjectMapper().readTree(safe).path("token").asText()).isEqualTo("REDACTED");
        assertThat(scanner.containsCredential(safe)).isFalse();
        assertThat(scanner.redact("https://example.org/?%74oken=CanaryValue98765&count=3"))
                .isEqualTo("https://example.org/?%74oken=REDACTED&count=3");
    }

    @Test
    void mergesOverlappingFindingsAndRemovesTheWholePrivateKeyBlock() {
        String input = "api_key=sk-CanaryValue987654321";
        assertThat(scanner.redact(input)).isEqualTo("api_key=REDACTED");
        String key = "-----BEGIN PRIVATE KEY-----\nCanaryMaterial\n-----END PRIVATE KEY-----\n保留";
        assertThat(scanner.redact(key)).isEqualTo("REDACTED\n保留");
        assertThat(scanner.findings(input)).allSatisfy(finding ->
                assertThat(finding.toString()).doesNotContain("Canary"));
    }
}
