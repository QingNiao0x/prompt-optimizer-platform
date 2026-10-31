package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptRoutingRepairInstructions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * v3复用动作、明确复述/报告、真实题答及来源保护回归；不以HTTP预置内容证明模型语义正确。
 * 冻结指纹来自上轮实际发送的v2指导，避免新补全策略静默改变历史或Plan行为。
 */
class NaturalCompletionBasisProviderTest extends NaturalActionBoundaryProviderTest {
    @Override
    protected String version() { return "core-repair-v3"; }

    @Test
    void onlyCandidateWritingChangesWhileLegacyAndPlanningStayFrozen() throws Exception {
        assertThat(NaturalPromptInstructions.enhancementInstruction("core-repair-v1"))
                .isEqualTo(NaturalPromptRoutingRepairInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.enhancementInstruction(version()))
                .isEqualTo(NaturalPromptCoreRepairInstructions.COMPLETION_ENHANCE)
                .isNotEqualTo(NaturalPromptInstructions.enhancementInstruction("core-repair-v2"));
        assertThat(NaturalPromptInstructions.planningInstruction(version()))
                .isEqualTo(NaturalPromptInstructions.planningInstruction("core-repair-v2"));
        assertThat(hash(NaturalPromptInstructions.enhancementInstruction("core-repair-v2")))
                .isEqualTo("da094a8ac05db630a33ab75496f017bf88c8bade6446d417a4394bc2a558a8bc");
        assertThat(hash(NaturalPromptCoreRepairInstructions.PREPARE))
                .isEqualTo("4fb43b9703231d810b6c8a1c923d0244aba9e40a5d9a5b7b1ea41f294b1f90fc");
        assertThat(hash(NaturalPromptInstructions.planningInstruction("core-repair-v2")))
                .isEqualTo("45bece6a7f7215e04c571c534d85c584665b8f76583eda991a153f72905ba221");
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
    }

    /** 固定实际发送字节，而非复述当前实现，保护历史指导与未调整的阶段。 */
    private String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
