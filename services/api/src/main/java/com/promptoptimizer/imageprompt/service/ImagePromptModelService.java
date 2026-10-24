package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.mapper.ImagePromptMapper;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 复用现有平台目录，只增加图像业务的显式视觉准入；不改变文本任务默认模型或路由。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptModelService {
    private final PlatformModelCatalog catalog;
    private final ImagePromptMapper mapper;
    private final ImagePromptHistoryService history;
    public ImagePromptModelService(PlatformModelCatalog catalog, ObjectProvider<ImagePromptMapper> mapper, ImagePromptHistoryService history) {
        this.catalog = catalog; this.mapper = mapper.getIfAvailable(); this.history = history;
    }

    /** 与文本增强使用相同的可用目录、名称及顺序；视觉能力只约束带图调用，不筛掉目录项。 */
    public List<Model> models() {
        return describe(catalog.available());
    }

    /** 管理员可以提前配置已停用模型的视觉能力；逻辑删除项仍由平台目录排除。 */
    public List<Model> adminModels() {
        return describe(catalog.all());
    }

    /** 将视觉准入附加到既有目录，模型发布、启停和排序始终由平台目录决定。 */
    private List<Model> describe(List<PlatformModelCatalog.ModelEntry> entries) {
        var capabilities = history.persistent() ? mapper.capabilities() : List.<VisionCapability>of();
        return entries.stream().map(m -> {
            var capability = capabilities.stream().filter(c -> c.modelId().equals(m.publicId())).findFirst();
            boolean mock = m.routeKey().equals("mock");
            return new Model(m.publicId(), m.displayName(), mock || capability.map(VisionCapability::enabled).orElse(false),
                    mock ? 3 : capability.map(VisionCapability::maxImages).orElse(0),
                    mock ? "本机演示，不代表真实视觉识别" : capability.map(VisionCapability::evidence).orElse("未登记视觉能力"), mock);
        }).toList();
    }

    /** 在模型调用前再次校验当前启用状态与图片数量，前端筛选不能替代此校验。 */
    public Model require(String id, int images) {
        catalog.resolve(id);
        var model = models().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow(() -> ImagePromptException.invalid("所选模型不可用，请刷新列表。"));
        if (images > 0 && (!model.vision() || images > model.maxImages())) throw ImagePromptException.invalid("所选模型未开放所需图片能力，请更换已验证的视觉模型。");
        return model;
    }

    /** 管理员显式登记依据；禁用模型与删除模型仍受原平台目录控制。 */
    public void configure(String id, boolean enabled, int maxImages, String evidence) {
        if (!history.persistent() || mapper == null) throw ImagePromptException.invalid("演示模式不支持修改持久模型准入。");
        if (catalog.all().stream().noneMatch(m -> m.publicId().equals(id))) throw ImagePromptException.invalid("请先在平台模型目录中添加该模型。");
        if (maxImages < 1 || maxImages > 3 || evidence == null || evidence.isBlank() || evidence.length() > 400) throw ImagePromptException.invalid("请填写有效的视觉能力依据和图片数量。");
        mapper.setCapability(new VisionCapability(id, enabled, maxImages, evidence.strip()));
    }
}
