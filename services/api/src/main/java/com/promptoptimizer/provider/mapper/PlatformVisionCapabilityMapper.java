package com.promptoptimizer.provider.mapper;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;

/**
 * 读取模型管理维护的视觉准入；沿用既有登记表，不创建第二份模型配置。
 * 仅包含公开模型标识与能力，不读取私有业务正文或密钥。
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface PlatformVisionCapabilityMapper {
    /** 全局模型目录排除逻辑删除；是否可用仍由平台模型目录复核。 */
    List<Capability> capabilities();
    /**
     * 对应 image_prompt_model_capability 的只读投影，modelId 引用 platform_model.public_id。
     * 全局配置不属于单个租户／工作区，不含材料或密钥；enabled 与 maxImages=1..3 由管理员登记。
     */
    record Capability(String modelId, boolean enabled, int maxImages, String evidence) { }
}
