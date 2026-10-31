package com.promptoptimizer.provider.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证指导减负与历史冻结；字符减少及字段隔离不替代真实模型的语义和作品验收。 */
class NaturalPromptSimplifiedInstructionsTest {
    @Test
    void simplifiedStagesReuseProtectionWithoutCrossStageBookkeeping() {
        String version = NaturalPromptSimplifiedInstructions.VERSION;
        String enhancement = NaturalPromptInstructions.enhancementInstruction(version);
        String planning = NaturalPromptInstructions.planningInstruction(version);
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
        assertThat(NaturalPromptStageInstructions.supports(version)).isTrue();
        assertThat(NaturalPromptStageInstructions.requiresFramework(version)).isFalse();
        assertThat(NaturalPromptStageInstructions.acceptsDiagnosticFields(version)).isFalse();
        assertThat(NaturalPromptRoutingRepairInstructions.supports(version)).isFalse();
        assertThat(enhancement).doesNotContain("taskUnderstanding", "requirementCoverage", "additionCoverage", "planImpacts", "questionId");
        assertThat(planning).doesNotContain("optimizedPrompt", "framework", "bodyQuotes", "Coverage", "taskUnderstanding", "planImpacts");
        assertThat(enhancement.length()).isLessThan(NaturalPromptCoreRepairInstructions.COMPLETION_ENHANCE.length());
        assertThat(planning.length()).isLessThan(NaturalPromptCoreRepairInstructions.PLAN.length());
        assertThat(NaturalPromptSimplifiedInstructions.PREPARE).isEqualTo(NaturalPromptCoreRepairInstructions.PREPARE);
    }

    @Test
    void v3StillMatchesItsPreviouslyRecordedRealRequest() throws Exception {
        // 指纹来自已封存natural-design-completion-2026-10-31/traces/0010.json的system字符串。
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                NaturalPromptInstructions.enhancementInstruction("core-repair-v3").getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("417b7f9aeddc857e8060c6211ce8556627c7ae663a0f28fefac2b75934418d12");
        assertThat(NaturalPromptInstructions.planningInstruction("core-repair-v3"))
                .isEqualTo(NaturalPromptCoreRepairInstructions.PLAN);
    }
}
