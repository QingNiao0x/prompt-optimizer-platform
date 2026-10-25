package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.domain.ContextVisionModels.Model;
import com.promptoptimizer.context.service.DocumentUploadException;
import com.promptoptimizer.provider.mapper.PlatformVisionCapabilityMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 将平台统一模型目录和现有视觉准入组合为上下文模型选择；不按名称推断视觉支持。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ContextVisionModelCatalog {
    private final PlatformModelCatalog catalog;
    private final PlatformVisionCapabilityMapper mapper;
    public ContextVisionModelCatalog(PlatformModelCatalog catalog, ObjectProvider<PlatformVisionCapabilityMapper> mapper) {
        this.catalog = catalog; this.mapper = mapper.getIfAvailable();
    }
    /** 全部已发布模型保持目录顺序，未登记项显示但不能带图调用。 */
    public List<Model> models() {
        var capabilities = mapper == null ? List.<PlatformVisionCapabilityMapper.Capability>of() : mapper.capabilities();
        return catalog.available().stream().map(model -> {
            var capability = capabilities.stream().filter(c -> c.modelId().equals(model.publicId())).findFirst();
            boolean mock = "mock".equals(model.routeKey());
            return new Model(model.publicId(), model.displayName(), mock || capability.map(c -> c.enabled() && c.maxImages() > 0).orElse(false),
                    mock ? 1 : capability.map(PlatformVisionCapabilityMapper.Capability::maxImages).orElse(0),
                    mock ? "本机演示，不代表真实识别" : capability.map(PlatformVisionCapabilityMapper.Capability::evidence).orElse("未登记视觉能力"), mock);
        }).toList();
    }
    /** 确认时和实际调用前都复核，管理员停用后不能凭旧页面继续发送。 */
    public Model require(String id) {
        if (id == null || id.isBlank()) throw invalid("请选择已开放视觉能力的模型。");
        try { catalog.resolve(id); }
        catch (com.promptoptimizer.common.exception.InvalidOptimizationRequestException exception) {
            throw invalid("所选视觉模型已停用或不可用，请刷新模型目录。");
        }
        return models().stream().filter(m -> m.id().equals(id) && m.vision()).findFirst()
                .orElseThrow(() -> invalid("所选模型未开放图片输入，请在模型管理登记能力或更换视觉模型。"));
    }
    private DocumentUploadException invalid(String message) {
        return new DocumentUploadException(DocumentUploadException.Reason.INVALID_ARGUMENT, message);
    }
}
