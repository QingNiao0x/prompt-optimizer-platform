package com.promptoptimizer.settings.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.ResourceNotFoundException;
import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;
import com.promptoptimizer.settings.api.ProviderConfigSaveRequest;
import com.promptoptimizer.settings.api.ProviderConfigUpdateRequest;
import com.promptoptimizer.settings.domain.ProviderConfigSummary;
import com.promptoptimizer.settings.infrastructure.ProviderConfigEntity;
import com.promptoptimizer.settings.infrastructure.ProviderConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认 Provider 配置服务，负责 API Key 加密、摘要脱敏和持久化。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class DefaultProviderConfigService implements ProviderConfigService {

    private static final String KEY_VERSION = "v1";

    private final ProviderConfigRepository repository;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final String encryptionSecret;

    public DefaultProviderConfigService(
            ProviderConfigRepository repository,
            CurrentActor currentActor,
            ObjectMapper objectMapper,
            @Value("${app.security.api-key-encryption-secret:}") String encryptionSecret
    ) {
        this.repository = repository;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.encryptionSecret = encryptionSecret;
    }

    /**
     * 查询当前认证主体默认工作区下的配置摘要列表。
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProviderConfigSummary> list() {
        ActorIdentity context = currentActor.require();
        return repository.findByTenantIdAndWorkspaceIdOrderByUpdatedAtDesc(
                        context.tenantId(),
                        context.workspaceId()
                ).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 创建配置，加密 API Key 并只暴露末四位。
     */
    @Override
    @Transactional
    public ProviderConfigSummary create(ProviderConfigSaveRequest request) {
        ActorIdentity context = currentActor.require();
        ApiKeyCipher cipher = cipher();

        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(context.tenantId());
        entity.setWorkspaceId(context.workspaceId());
        entity.setCreatedBy(context.userId());
        entity.setProviderType(request.providerType());
        entity.setDisplayName(request.displayName());
        entity.setEndpointUrl(request.endpointUrl());
        entity.setModelName(request.modelName());
        entity.setApiKeyCiphertext(cipher.encrypt(request.apiKey()));
        entity.setKeyVersion(KEY_VERSION);
        entity.setApiKeyLast4(cipher.last4(request.apiKey()));
        entity.setParameters(normalizeParameters(request.parameters()));
        entity.setEnabled(request.enabled());

        return toSummary(repository.save(entity));
    }

    /**
     * 按 ID 更新配置，仅更新请求中出现的字段。
     */
    @Override
    @Transactional
    public ProviderConfigSummary update(UUID id, ProviderConfigUpdateRequest request) {
        ActorIdentity context = currentActor.require();
        ProviderConfigEntity entity = repository.findByIdAndTenantIdAndWorkspaceId(
                        id,
                        context.tenantId(),
                        context.workspaceId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Provider 配置不存在或无权访问"));

        if (request.displayName() != null) {
            entity.setDisplayName(request.displayName());
        }
        if (request.endpointUrl() != null) {
            entity.setEndpointUrl(request.endpointUrl());
        }
        if (request.modelName() != null) {
            entity.setModelName(request.modelName());
        }
        if (request.parameters() != null) {
            entity.setParameters(normalizeParameters(request.parameters()));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            ApiKeyCipher cipher = cipher();
            entity.setApiKeyCiphertext(cipher.encrypt(request.apiKey()));
            entity.setKeyVersion(KEY_VERSION);
            entity.setApiKeyLast4(cipher.last4(request.apiKey()));
        }

        return toSummary(repository.save(entity));
    }

    /**
     * 删除当前工作区下的配置。
     */
    @Override
    @Transactional
    public void delete(UUID id) {
        ActorIdentity context = currentActor.require();
        ProviderConfigEntity entity = repository.findByIdAndTenantIdAndWorkspaceId(
                        id,
                        context.tenantId(),
                        context.workspaceId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Provider 配置不存在或无权访问"));
        repository.delete(entity);
    }

    /**
     * 使用运行环境注入的加密主密钥创建加密器。
     */
    private ApiKeyCipher cipher() {
        return new ApiKeyCipher(encryptionSecret);
    }

    /**
     * 规范化参数映射，只保留可序列化值。
     */
    private Map<String, Object> normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // 只保留可序列化值，避免把任意对象写入 JSONB。
        return objectMapper.convertValue(parameters, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 把实体转换为不包含明文 Key 的对外摘要。
     */
    private ProviderConfigSummary toSummary(ProviderConfigEntity entity) {
        return new ProviderConfigSummary(
                entity.getId(),
                entity.getProviderType(),
                entity.getDisplayName(),
                entity.getEndpointUrl(),
                entity.getModelName(),
                entity.getApiKeyLast4(),
                entity.getParameters(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
