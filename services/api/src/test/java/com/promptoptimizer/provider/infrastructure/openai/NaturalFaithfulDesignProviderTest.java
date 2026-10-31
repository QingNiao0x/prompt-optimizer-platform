package com.promptoptimizer.provider.infrastructure.openai;

/** 一次共同保真修正继续复用同样的来源、实际题答、动作、报告与等待边界；语义仍需真实原件检查。 */
class NaturalFaithfulDesignProviderTest extends NaturalDesignCompletionProviderTest {
    @Override
    protected String version() { return "core-design-completion-v2"; }
}
