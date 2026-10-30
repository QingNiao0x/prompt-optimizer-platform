package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 自然增强新请求的服务端采用开关；历史显式版本、结构化链及其他工具不使用此开关。
 * 默认继续稳定配置，工程修复可独立试用及回退；不能从浏览器请求选择任意实验版本。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.enhancement.natural")
public class NaturalEnhancementSettings {
    /** STABLE保留已采用的默认；CORE_REPAIR只启用已说明范围的公共流程修复。 */
    public enum Profile { STABLE, CORE_REPAIR }

    @NotNull
    private Profile profile = Profile.STABLE;

    /** 读取服务端配置，不从模型输出或用户任务文本推断采用策略。 */
    public Profile getProfile() { return profile; }

    /** 非法或空配置在绑定/启动时失败，不静默换成其他候选。 */
    public void setProfile(Profile profile) { this.profile = java.util.Objects.requireNonNull(profile); }

    /** 新请求、访客增强与Plan使用一致的策略；保存历史的调用方仍显式指定原版本。 */
    public String strategyVersion() {
        return switch (profile) {
            case STABLE -> NaturalPromptInstructions.CURRENT_VERSION;
            case CORE_REPAIR -> NaturalPromptCoreRepairInstructions.VERSION;
        };
    }
}
