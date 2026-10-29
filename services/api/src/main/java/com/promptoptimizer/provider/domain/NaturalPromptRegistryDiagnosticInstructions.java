package com.promptoptimizer.provider.domain;

/**
 * 51仅为引用正文协议增加局部诊断兼容，不改变50的准备、写作或Plan指导。
 * 旧版本reader保持冻结；版本选择不引入新阶段、补引文或语义放行。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptRegistryDiagnosticInstructions {
    public static final String VERSION = "51";
    public static final String PREPARE = NaturalPromptContextBoundaryRegistryInstructions.PREPARE;
    public static final String ENHANCE = NaturalPromptContextBoundaryRegistryInstructions.ENHANCE;
    public static final String PLAN = NaturalPromptContextBoundaryRegistryInstructions.PLAN;

    private NaturalPromptRegistryDiagnosticInstructions() { }
}
