package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.provider.mapper.PlatformModelMapper;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 从 PostgreSQL 读取管理员维护的模型目录；仅路由端点和密钥仍由部署配置提供。
 * 无数据库的 local-mock 测试保留一个固定演示模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class PlatformModelCatalogService implements PlatformModelCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformModelCatalogService.class);
    private static final Pattern SAFE_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}");
    private static final ModelEntry MOCK_MODEL = new ModelEntry(
            UUID.nameUUIDFromBytes("mock:deterministic-enhancer-v1".getBytes(StandardCharsets.UTF_8)),
            "mock:deterministic-enhancer-v1", "mock", "deterministic-enhancer-v1",
            "演示模型", true, true, 0
    );
    private final PlatformModelMapper mapper;
    private final OpenAiCompatibleProperties properties;

    public PlatformModelCatalogService(
            ObjectProvider<PlatformModelMapper> mapperProvider,
            ObjectProvider<OpenAiCompatibleProperties> propertiesProvider
    ) {
        this.mapper = mapperProvider.getIfAvailable();
        this.properties = propertiesProvider.getIfAvailable();
    }

    /**
     * 从部署路由导入目录中还没有的模型。
     * 已有记录保持管理员的启用、排序和默认选择，不用配置覆盖。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        if (mapper == null || properties == null) {
            return;
        }
        boolean catalogEmpty = countAll() == 0;
        OpenAiCompatibleRoute defaultRoute = properties.getDefaultRoute();
        List<ModelEntry> discovered = new ArrayList<>();
        for (OpenAiCompatibleRoute route : properties.getConfiguredRoutes()) {
            for (String model : route.models()) {
                String publicId = properties.publicModelId(route, model);
                validatePublicId(publicId);
                boolean isDefault = catalogEmpty
                        && route.key().equals(defaultRoute.key())
                        && model.equals(defaultRoute.model());
                discovered.add(new ModelEntry(
                        UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8)),
                        publicId, route.key(), model, displayName(model), true, isDefault,
                        discovered.size()
                ));
            }
        }
        if (catalogEmpty) {
            discovered.sort(Comparator.comparing(ModelEntry::defaultModel).reversed());
        }
        for (ModelEntry model : discovered) {
            mapper.insertIfAbsent(model.id(), model.publicId(), model.routeKey(), model.upstreamModel(),
                    model.displayName(), model.enabled(), model.defaultModel(), model.sortOrder());
        }
        LOGGER.info("event=platform.model.catalog.initialized modelCount={}", countAll());
    }

    @Override
    public List<ModelEntry> available() {
        return all().stream()
                .filter(ModelEntry::enabled)
                .filter(model -> routeAvailable(model.routeKey()))
                .toList();
    }

    @Override
    public ModelEntry resolve(String requestedId) {
        String requested = requestedId == null ? "" : requestedId.trim();
        List<ModelEntry> candidates = available();
        ModelEntry selected = candidates.stream()
                .filter(model -> requested.isBlank() ? model.defaultModel() : model.publicId().equals(requested))
                .findFirst()
                .orElseThrow(() -> new InvalidOptimizationRequestException(
                        requested.isBlank()
                                ? "平台尚未配置可用的默认模型，请联系管理员。"
                                : "所选模型不存在、已停用或当前不可用，请刷新模型列表。"));
        return selected;
    }

    @Override
    public List<ModelEntry> all() {
        if (mapper == null || properties == null) {
            return properties == null ? List.of(MOCK_MODEL) : configuredModels();
        }
        return mapper.selectAllActive();
    }

    @Override
    public List<RouteEntry> routes() {
        if (properties == null) {
            return List.of();
        }
        return properties.getConfiguredRoutes().stream()
                .map(route -> new RouteEntry(route.key(), route.providerName()))
                .toList();
    }

    /** 管理员只能把已配置供应商路由下的模型加入目录，不能写入端点或密钥。 */
    @Override
    @Transactional
    public ModelEntry create(ModelChange change) {
        requirePersistentCatalog();
        OpenAiCompatibleRoute route = requireRoute(change.routeKey());
        String upstreamModel = validateModel(change.upstreamModel());
        String displayName = validateDisplayName(change.displayName());
        int sortOrder = validateSortOrder(change.sortOrder());
        String publicId = properties.publicModelId(route, upstreamModel);
        validatePublicId(publicId);
        if (mapper.selectActiveByPublicId(publicId) != null) {
            throw new InvalidOptimizationRequestException("该模型已在平台目录中，请直接修改现有记录。");
        }
        boolean defaultModel = change.defaultModel() || !hasDefault();
        if (defaultModel && !change.enabled()) {
            throw new InvalidOptimizationRequestException("默认模型必须处于启用状态。");
        }
        if (defaultModel) clearDefault();
        UUID deletedId = mapper.selectDeletedId(publicId);
        if (deletedId != null) {
            mapper.restore(deletedId, displayName, change.enabled(), defaultModel, sortOrder);
            LOGGER.info("event=platform.model.restored modelId={}", publicId);
            return requireById(deletedId);
        }
        UUID id = UUID.randomUUID();
        mapper.insert(id, publicId, route.key(), upstreamModel, displayName,
                change.enabled(), defaultModel, sortOrder);
        LOGGER.info("event=platform.model.created modelId={}", publicId);
        return requireById(id);
    }

    @Override
    @Transactional
    public ModelEntry update(UUID id, ModelChange change) {
        requirePersistentCatalog();
        ModelEntry current = requireById(id);
        if (!current.routeKey().equals(change.routeKey())
                || !current.upstreamModel().equals(change.upstreamModel())) {
            throw new InvalidOptimizationRequestException("模型路由和上游名称不可直接修改，请新增模型后停用旧模型。");
        }
        if (current.defaultModel() && (!change.enabled() || !change.defaultModel())) {
            throw new InvalidOptimizationRequestException("请先设置其他默认模型，再停用当前默认模型。");
        }
        if (change.defaultModel() && !change.enabled()) {
            throw new InvalidOptimizationRequestException("默认模型必须处于启用状态。");
        }
        if (change.defaultModel() && !current.defaultModel()) clearDefault();
        mapper.update(id, validateDisplayName(change.displayName()), change.enabled(),
                change.defaultModel(), validateSortOrder(change.sortOrder()));
        LOGGER.info("event=platform.model.updated modelId={}", current.publicId());
        return requireById(id);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        requirePersistentCatalog();
        ModelEntry current = requireById(id);
        if (current.defaultModel()) {
            throw new InvalidOptimizationRequestException("请先设置其他默认模型，再删除当前默认模型。");
        }
        mapper.softDelete(id);
        LOGGER.info("event=platform.model.deleted modelId={}", current.publicId());
    }

    private List<ModelEntry> configuredModels() {
        OpenAiCompatibleRoute defaultRoute = properties.getDefaultRoute();
        List<ModelEntry> entries = new ArrayList<>();
        for (OpenAiCompatibleRoute route : properties.getConfiguredRoutes()) {
            for (String model : route.models()) {
                String id = properties.publicModelId(route, model);
                entries.add(new ModelEntry(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                        id, route.key(), model, displayName(model), true,
                        route.key().equals(defaultRoute.key()) && model.equals(defaultRoute.model()), entries.size()));
            }
        }
        return List.copyOf(entries);
    }

    private boolean routeAvailable(String key) {
        return properties == null ? "mock".equals(key) : properties.getConfiguredRoutes().stream()
                .anyMatch(route -> route.key().equals(key));
    }

    private OpenAiCompatibleRoute requireRoute(String key) {
        return properties.getConfiguredRoutes().stream()
                .filter(route -> route.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new InvalidOptimizationRequestException("供应商路由未在服务端配置或不可用。"));
    }

    private ModelEntry requireById(UUID id) {
        ModelEntry entry = mapper.selectByIdActive(id);
        if (entry == null) {
            throw new InvalidOptimizationRequestException("模型不存在或已删除。");
        }
        return entry;
    }

    private boolean hasDefault() {
        return mapper.hasEnabledDefault();
    }

    private void clearDefault() {
        mapper.clearDefault();
    }

    private int countAll() {
        return mapper.countAll();
    }

    private void requirePersistentCatalog() {
        if (mapper == null || properties == null) {
            throw new InvalidOptimizationRequestException("当前演示环境不支持修改平台模型目录。");
        }
    }

    private String validateModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (!SAFE_MODEL.matcher(normalized).matches()) {
            throw new InvalidOptimizationRequestException("上游模型名称格式无效。");
        }
        return normalized;
    }

    private String validateDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (normalized.isBlank() || normalized.length() > 120) {
            throw new InvalidOptimizationRequestException("模型展示名称不能为空且不能超过 120 个字符。");
        }
        return normalized;
    }

    private void validatePublicId(String publicId) {
        if (publicId.length() > 160) {
            throw new InvalidOptimizationRequestException("模型公开标识不能超过 160 个字符，请缩短路由或模型名称。");
        }
    }

    private int validateSortOrder(int value) {
        if (value < 0 || value > 10_000) {
            throw new InvalidOptimizationRequestException("模型排序值必须在 0 到 10000 之间。");
        }
        return value;
    }

    private static String displayName(String model) {
        return switch (model) {
            case "deepseek-flash" -> "DeepSeek-V4.1-Flash";
            case "deepseek-v4-pro" -> "DeepSeek-V4-Pro-0813";
            case "deepseek-v4-pro-0813" -> "DeepSeek-V4-Pro";
            case "kimi-k3" -> "Kimi K3";
            case "kimi-k2.8-preview" -> "Kimi K2.8 Preview";
            case "kimi-k2.7-code" -> "Kimi K2.7 Code";
            case "glm-5.3" -> "GLM-5.3";
            case "glm-5.3-flashx" -> "GLM-5.3-FlashX";
            case "hy4-preview" -> "Hy4 preview";
            case "hy3" -> "Hy3";
            case "minimax-m3" -> "MiniMax-M3";
            default -> model;
        };
    }
}
