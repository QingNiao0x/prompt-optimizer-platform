package com.promptoptimizer.settings.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCipherTest {

    private final ApiKeyCipher cipher = new ApiKeyCipher("unit-test-encryption-secret");

    @Test
    void shouldEncryptAndDecryptApiKey() {
        String plaintext = "sk-unit-test-key-1234567890";

        String ciphertext = cipher.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void shouldUseRandomIvForSamePlaintext() {
        String plaintext = "sk-unit-test-key-1234567890";

        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldReturnLastFourCharacters() {
        assertThat(cipher.last4("sk-unit-test-1234")).isEqualTo("1234");
        assertThat(cipher.last4("")).isEmpty();
    }

    @Test
    void shouldRejectMissingEncryptionSecret() {
        assertThatThrownBy(() -> new ApiKeyCipher("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_KEY_ENCRYPTION_SECRET");
    }
}
