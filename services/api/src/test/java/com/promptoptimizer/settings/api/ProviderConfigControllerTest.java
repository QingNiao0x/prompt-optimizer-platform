package com.promptoptimizer.settings.api;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.common.exception.EncryptionSecretMissingException;
import com.promptoptimizer.settings.application.ProviderConfigService;
import com.promptoptimizer.settings.domain.ProviderConfigSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProviderConfigController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class ProviderConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderConfigService providerConfigService;

    @Test
    void shouldReturnConfigSummaryWithoutPlaintextKey() throws Exception {
        when(providerConfigService.list()).thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/v1/provider-configs"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data[0].modelName").value("deepseek-chat"))
                .andExpect(jsonPath("$.data[0].apiKeyLast4").value("1234"))
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist());
    }

    @Test
    void shouldCreateConfig() throws Exception {
        when(providerConfigService.create(any())).thenReturn(summary());

        mockMvc.perform(post("/api/v1/provider-configs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "providerType": "DEEPSEEK",
                                  "displayName": "DeepSeek",
                                  "endpointUrl": "https://api.deepseek.com/chat/completions",
                                  "modelName": "deepseek-chat",
                                  "apiKey": "sk-test-only",
                                  "parameters": {"temperature": 0.2},
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"));
    }

    @Test
    void shouldUpdateConfig() throws Exception {
        when(providerConfigService.update(any(), any())).thenReturn(summary());

        mockMvc.perform(patch("/api/v1/provider-configs/{id}", UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"modelName": "deepseek-chat", "enabled": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"));
    }

    @Test
    void shouldDeleteConfig() throws Exception {
        mockMvc.perform(delete("/api/v1/provider-configs/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void shouldRejectInvalidProviderType() throws Exception {
        mockMvc.perform(post("/api/v1/provider-configs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "providerType": "UNKNOWN",
                                  "displayName": "Test",
                                  "endpointUrl": "https://example.com",
                                  "modelName": "test",
                                  "apiKey": "sk-test-only"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldReturnConfigurationErrorWhenEncryptionSecretIsMissing() throws Exception {
        when(providerConfigService.create(any()))
                .thenThrow(new EncryptionSecretMissingException());

        mockMvc.perform(post("/api/v1/provider-configs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "providerType": "DEEPSEEK",
                                  "displayName": "DeepSeek",
                                  "endpointUrl": "https://api.deepseek.com/chat/completions",
                                  "modelName": "deepseek-chat",
                                  "apiKey": "sk-test-only"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_CONFIGURATION_ERROR"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    private ProviderConfigSummary summary() {
        return new ProviderConfigSummary(
                UUID.randomUUID(),
                "DEEPSEEK",
                "DeepSeek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-chat",
                "1234",
                Map.of("temperature", 0.2),
                true,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
