package com.promptoptimizer.settings.application;

import com.promptoptimizer.common.exception.ResourceNotFoundException;
import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;
import com.promptoptimizer.settings.api.ProviderConfigSaveRequest;
import com.promptoptimizer.settings.api.ProviderConfigUpdateRequest;
import com.promptoptimizer.settings.domain.ProviderConfigSummary;
import com.promptoptimizer.settings.infrastructure.ProviderConfigEntity;
import com.promptoptimizer.settings.infrastructure.ProviderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultProviderConfigServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private ProviderConfigRepository repository;

    @Mock
    private CurrentActor currentActor;

    private DefaultProviderConfigService service;

    @BeforeEach
    void setUp() {
        when(currentActor.require()).thenReturn(new ActorIdentity(
                USER_ID,
                TENANT_ID,
                WORKSPACE_ID,
                "demo@local",
                "Local Demo User"
        ));
        service = new DefaultProviderConfigService(
                repository,
                currentActor,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "unit-test-encryption-secret"
        );
    }

    @Test
    void shouldEncryptApiKeyAndExposeOnlyLastFour() {
        when(repository.save(any(ProviderConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String apiKey = "sk-unit-test-service-1234";

        ProviderConfigSummary summary = service.create(new ProviderConfigSaveRequest(
                "DEEPSEEK",
                "DeepSeek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-chat",
                apiKey,
                Map.of("temperature", 0.2),
                true
        ));

        ArgumentCaptor<ProviderConfigEntity> captor = ArgumentCaptor.forClass(ProviderConfigEntity.class);
        verify(repository).save(captor.capture());
        ProviderConfigEntity saved = captor.getValue();

        assertThat(saved.getApiKeyCiphertext()).isNotEqualTo(apiKey);
        assertThat(saved.getApiKeyCiphertext()).doesNotContain(apiKey);
        assertThat(saved.getApiKeyLast4()).isEqualTo("1234");
        assertThat(summary.apiKeyLast4()).isEqualTo("1234");
    }

    @Test
    void shouldReEncryptWhenUpdateIncludesNewApiKey() {
        ProviderConfigEntity entity = newEntity();
        when(repository.findByIdAndTenantIdAndWorkspaceId(entity.getId(), TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(ProviderConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String newKey = "sk-unit-test-updated-5678";
        service.update(entity.getId(), new ProviderConfigUpdateRequest(
                null,
                null,
                null,
                newKey,
                null,
                null
        ));

        assertThat(entity.getApiKeyCiphertext()).isNotEqualTo(newKey);
        assertThat(entity.getApiKeyLast4()).isEqualTo("5678");
    }

    @Test
    void shouldThrowNotFoundWhenConfigDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.findByIdAndTenantIdAndWorkspaceId(missingId, TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(missingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private ProviderConfigEntity newEntity() {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT_ID);
        entity.setWorkspaceId(WORKSPACE_ID);
        entity.setCreatedBy(USER_ID);
        entity.setProviderType("DEEPSEEK");
        entity.setDisplayName("DeepSeek");
        entity.setEndpointUrl("https://api.deepseek.com/chat/completions");
        entity.setModelName("deepseek-chat");
        entity.setApiKeyCiphertext("ciphertext");
        entity.setKeyVersion("v1");
        entity.setApiKeyLast4("1234");
        entity.setParameters(new LinkedHashMap<>());
        entity.setEnabled(true);
        return entity;
    }
}
