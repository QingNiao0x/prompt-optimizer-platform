package com.promptoptimizer.provider.domain;

/**
 * 可独立验收的工程修复标识，复用41的范围、正文和Plan指导，不新增写作模板。
 * 独立标识只用于保存实际处理路径和限定来源修复反馈；31及已有候选的历史协议不回改。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptCoreRepairInstructions {
    public static final String VERSION = "core-repair-v1";

    private NaturalPromptCoreRepairInstructions() { }
}
