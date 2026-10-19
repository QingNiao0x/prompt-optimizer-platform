package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ProviderMetadata;

/**
 * 模型生成的自然正文候选；框架仅用于诊断，不能建立事实、权限或用户确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptDraft(String optimizedPrompt, Framework framework, ProviderMetadata provider) {
    /** 截图中的十二种组织参考；每次择一，不要求补齐角色、步骤或示例槽位。 */
    public enum Framework {
        /** 动作、目的与预期；无法可靠匹配特定组织参考时回退。 */
        APE,
        /** 背景、目标与改进，适用于确有迭代要求的任务。 */
        BROKE,
        /** 围绕已知情境组织目标和行动。 */
        COAST,
        /** 任务、行动与目标，适用于直接行动指令。 */
        TAG,
        /** 输入、步骤与预期，适用于教学和操作指导。 */
        RISE,
        /** 任务、请求、操作和上下文，适用于有具体处理请求的任务。 */
        TRACE,
        /** 情境、行动与结果，适用于基于材料的解释与沟通。 */
        CARE,
        /** 预期、必要角色视角与行动；不自动添加专业身份。 */
        ERA,
        /** 有依据的背景、行动与预期。 */
        RACE,
        /** 情境、方案与实施步骤；方案属于拟议设计而非既定事实。 */
        ROSES,
        /** 指令、背景、输入和输出，适用于提取与转换。 */
        ICIO,
        /** 明确风格和创意要求；不默认生成多份候选或额外实验。 */
        CRISPE
    }
}
