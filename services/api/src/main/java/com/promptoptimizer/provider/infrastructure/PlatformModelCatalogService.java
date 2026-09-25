package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.provider.application.PlatformModelCatalog;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final RowMapper<ModelEntry> MODEL_MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;
    private final OpenAiCompatibleProperties properties;

    public PlatformModelCatalogService(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectProvider<OpenAiCompatibleProperties> propertiesProvider
    ) {
        this.jdbc = jdbcProvider.getIfAvailable();
        this.properties = propertiesProvider.getIfAvailable();
    }

    /** 首次启动时从部署路由导入可用模型；后续启动不覆盖管理员的增删改。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        if (jdbc == null || properties == null || countAll() > 0) {
            return;
        }
        OpenAiCompatibleRoute defaultRoute = properties.getDefaultRoute();
        List<OpenAiCompatibleRoute> routes = properties.getConfiguredRoutes();
        List<ModelEntry> initial = new ArrayList<>();
        for (OpenAiCompatibleRoute route : routes) {
            for (String model : route.models()) {
                String publicId = properties.publicModelId(route, model);
                validatePublicId(publicId);
                boolean isDefault = route.key().equals(defaultRoute.key()) && model.equals(defaultRoute.model());
                initial.add(new ModelEntry(
                        UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8)),
                        publicId, route.key(), model, displayName(model), true, isDefault,
                        initial.size()
                ));
            }
        }
        initial.sort(Comparator.comparing(ModelEntry::defaultModel).reversed());
        for (ModelEntry model : initial) {
            jdbc.update("""
                    INSERT INTO platform_model
                        (id, public_id, route_key, upstream_model, display_name,
                         enabled, default_model, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (public_id) DO NOTHING
                    """, model.id(), model.publicId(), model.routeKey(), model.upstreamModel(),
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
        if (jdbc == null || properties == null) {
            return properties == null ? List.of(MOCK_MODEL) : configuredModels();
        }
        return jdbc.query("""
                SELECT id, public_id, route_key, upstream_model, display_name,
                       enabled, default_model, sort_order
                FROM platform_model WHERE deleted_at IS NULL
                ORDER BY sort_order, public_id
                """, MODEL_MAPPER);
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
        List<ModelEntry> existing = jdbc.query("""
                SELECT id, public_id, route_key, upstream_model, display_name,
                       enabled, default_model, sort_order
                FROM platform_model WHERE public_id = ? AND deleted_at IS NULL
                """, MODEL_MAPPER, publicId);
        if (!existing.isEmpty()) {
            throw new InvalidOptimizationRequestException("该模型已在平台目录中，请直接修改现有记录。");
        }
        boolean defaultModel = change.defaultModel() || !hasDefault();
        if (defaultModel && !change.enabled()) {
            throw new InvalidOptimizationRequestException("默认模型必须处于启用状态。");
        }
        if (defaultModel) clearDefault();
        List<UUID> deletedIds = jdbc.query(
                "SELECT id FROM platform_model WHERE public_id = ? AND deleted_at IS NOT NULL",
                (rs, rowNum) -> rs.getObject("id", UUID.class), publicId);
        if (!deletedIds.isEmpty()) {
            UUID restoredId = deletedIds.getFirst();
            jdbc.update("""
                    UPDATE platform_model
                    SET display_name = ?, enabled = ?, default_model = ?, sort_order = ?,
                        deleted_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, displayName, change.enabled(), defaultModel, sortOrder, restoredId);
            LOGGER.info("event=platform.model.restored modelId={}", publicId);
            return requireById(restoredId);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_model
                    (id, public_id, route_key, upstream_model, display_name,
                     enabled, default_model, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, publicId, route.key(), upstreamModel, displayName,
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
        jdbc.update("""
                UPDATE platform_model
                SET display_name = ?, enabled = ?, default_model = ?, sort_order = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND deleted_at IS NULL
                """, validateDisplayName(change.displayName()), change.enabled(),
                change.defaultModel(), validateSortOrder(change.sortOrder()), id);
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
        jdbc.update("""
                UPDATE platform_model SET enabled = FALSE, deleted_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND deleted_at IS NULL
                """, id);
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
        return jdbc.query("""
                SELECT id, public_id, route_key, upstream_model, display_name,
                       enabled, default_model, sort_order
                FROM platform_model WHERE id = ? AND deleted_at IS NULL
                """, MODEL_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new InvalidOptimizationRequestException("模型不存在或已删除。"));
    }

    private boolean hasDefault() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_model
                WHERE default_model = TRUE AND enabled = TRUE AND deleted_at IS NULL
                """, Integer.class);
        return count != null && count > 0;
    }

    private void clearDefault() {
        jdbc.update("""
                UPDATE platform_model SET default_model = FALSE, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE default_model = TRUE AND deleted_at IS NULL
                """);
    }

    private int countAll() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model", Integer.class);
        return count == null ? 0 : count;
    }

    private void requirePersistentCatalog() {
        if (jdbc == null || properties == null) {
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

    private static ModelEntry map(ResultSet rs) throws SQLException {
        return new ModelEntry(rs.getObject("id", UUID.class), rs.getString("public_id"),
                rs.getString("route_key"), rs.getString("upstream_model"),
                rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getBoolean("default_model"), rs.getInt("sort_order"));
    }

    private static String displayName(String model) {
        return switch (model) {
            case "deepseek-flash" -> "DeepSeek-V4.1-Flash";
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
