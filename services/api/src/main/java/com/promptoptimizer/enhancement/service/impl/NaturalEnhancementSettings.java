package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptPlanContextInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptSimplifiedInstructions;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 自然增强新请求的服务端采用开关；历史显式版本、结构化链及其他工具不使用此开关。
 * 新请求采用已独立验证的门控、材料与动作边界修复；STABLE保留31回退，浏览器不能选择实验版本。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.enhancement.natural")
public class NaturalEnhancementSettings {
    /** 默认v4修复已答状态与来源关联；显式旧配置不被改写，历史仍按保存版本。 */
    public enum Profile {
        STABLE, CORE_REPAIR, ACTION_BOUNDARY_REPAIR, COMPLETION_BASIS_REPAIR,
        /** 采用已验证的阶段协议、已答状态及来源关联修复，继续沿用v3正文补全原则。 */ PLAN_CONTEXT_REPAIR,
        /** 精简正文指导尚未证明作品收益，仅供显式对照，不随工程修复默认采用。 */ SIMPLIFIED
    }

    @NotNull
    private Profile profile = Profile.PLAN_CONTEXT_REPAIR;

    /** 读取服务端配置，不从模型输出或用户任务文本推断采用策略。 */
    public Profile getProfile() { return profile; }

    /** 非法或空配置在绑定/启动时失败，不静默换成其他候选。 */
    public void setProfile(Profile profile) { this.profile = java.util.Objects.requireNonNull(profile); }

    /** 新请求、访客增强与Plan使用一致的策略；保存历史的调用方仍显式指定原版本。 */
    public String strategyVersion() {
        return switch (profile) {
            case STABLE -> NaturalPromptInstructions.CURRENT_VERSION;
            case CORE_REPAIR -> NaturalPromptCoreRepairInstructions.VERSION;
            case ACTION_BOUNDARY_REPAIR -> NaturalPromptCoreRepairInstructions.ACTION_BOUNDARY_VERSION;
            case COMPLETION_BASIS_REPAIR -> NaturalPromptCoreRepairInstructions.COMPLETION_BASIS_VERSION;
            case PLAN_CONTEXT_REPAIR -> NaturalPromptPlanContextInstructions.VERSION;
            case SIMPLIFIED -> NaturalPromptSimplifiedInstructions.VERSION;
        };
    }
}
