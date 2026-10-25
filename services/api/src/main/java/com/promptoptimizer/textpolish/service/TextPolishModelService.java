package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 平台模型管理中的润色专属准入和默认模型；全局启停、逻辑删除与路由可用性优先。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class TextPolishModelService {
    private final PlatformModelCatalog catalog;
    private final TextPolishMapper mapper;
    private final ObjectMapper json;
    public TextPolishModelService(PlatformModelCatalog catalog, ObjectProvider<TextPolishMapper> mapper, ObjectMapper json,
                                  @Value("${app.history.enabled:true}") boolean persistent) {
        this.catalog = catalog; this.mapper = persistent ? mapper.getIfAvailable() : null; this.json = json;
    }
    /** 演示模式沿用目录，持久模式严格使用管理员配置，不在故障时放开模型。 */
    public ModelPolicy policy() {
        if (mapper == null) return new ModelPolicy(catalog.available().stream().map(PlatformModelCatalog.ModelEntry::publicId).toList(),
                catalog.available().stream().filter(PlatformModelCatalog.ModelEntry::defaultModel).map(PlatformModelCatalog.ModelEntry::publicId).findFirst().orElse(null));
        String value = mapper.policy();
        if (value == null) throw new IllegalStateException("润色模型配置缺失，请应用数据库迁移");
        try { return json.readValue(value, ModelPolicy.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("润色模型配置无法读取", failure); }
    }
    /** 公开列表不含供应商路由、端点或密钥。 */
    public List<Model> models() {
        ModelPolicy policy = policy();
        return catalog.available().stream().filter(m -> policy.enabledModelIds().contains(m.publicId()))
                .map(m -> new Model(m.publicId(), m.displayName(), m.publicId().equals(policy.defaultModelId()), m.routeKey().equals("mock"))).toList();
    }
    /** 调用前重新核对模块准入和全局目录，停用后历史不能绕过配置。 */
    public PlatformModelCatalog.ModelEntry require(String requested) {
        var candidates = models();
        String id = requested == null || requested.isBlank() ? candidates.stream().filter(Model::defaultModel).map(Model::id).findFirst()
                .orElseThrow(() -> TextPolishException.invalid("文章祛AI味尚无可用默认模型，请在模型管理中配置。")) : requested;
        if (candidates.stream().noneMatch(m -> m.id().equals(id))) throw TextPolishException.invalid("所选模型未开放文章润色或已停用，请刷新模型列表。");
        return catalog.resolve(id);
    }
    /** 同一次更新校验完整清单和默认值，不改变平台通用默认或图像能力。 */
    @Transactional
    public ModelPolicy configure(ModelPolicy input) {
        if (mapper == null) throw TextPolishException.invalid("演示模式不能保存模型配置。");
        var ids = input.enabledModelIds();
        var all = catalog.all();
        if (ids.size() != new HashSet<>(ids).size() || ids.stream().anyMatch(id -> all.stream().noneMatch(m -> m.publicId().equals(id))))
            throw TextPolishException.invalid("模型清单包含重复、已删除或不存在的项目。");
        String defaultId = input.defaultModelId();
        if (ids.isEmpty()) defaultId = null;
        else if (defaultId == null || !ids.contains(defaultId)) throw TextPolishException.invalid("请选择一个已加入文章润色的默认模型。");
        if (defaultId != null) catalog.resolve(defaultId);
        try {
            if (mapper.setPolicy(json.writeValueAsString(ids), defaultId) != 1) throw new IllegalStateException("润色模型配置缺失");
        } catch (JsonProcessingException failure) { throw new IllegalStateException("润色模型配置无法编码", failure); }
        return new ModelPolicy(List.copyOf(ids), defaultId);
    }
}
