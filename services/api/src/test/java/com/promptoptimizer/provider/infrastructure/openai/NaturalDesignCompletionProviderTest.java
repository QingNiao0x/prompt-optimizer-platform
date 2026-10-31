package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 设计补全候选复用动作、来源、实际题答和Plan回归；预置响应仅证明传输保护，不证明模型质量。
 * v3实际出站指纹来自上一轮007原件，新候选不得静默改变日常入口及旧历史的正文指导。
 */
class NaturalDesignCompletionProviderTest extends NaturalActionBoundaryProviderTest {
    @Override
    protected String version() { return "core-design-completion-v1"; }

    @Test
    void changesOnlyCandidateWritingAndKeepsPlanAndDailyGuidanceFrozen() throws Exception {
        assertThat(NaturalPromptInstructions.enhancementInstruction(version()))
                .isNotEqualTo(NaturalPromptInstructions.enhancementInstruction("core-repair-v3"));
        assertThat(NaturalPromptInstructions.planningInstruction(version()))
                .isEqualTo(NaturalPromptInstructions.planningInstruction("core-repair-v3"));
        assertThat(hash(NaturalPromptCoreRepairInstructions.PREPARE))
                .isEqualTo("4fb43b9703231d810b6c8a1c923d0244aba9e40a5d9a5b7b1ea41f294b1f90fc");
        assertThat(hash(NaturalPromptInstructions.enhancementInstruction("core-repair-v3")))
                .isEqualTo("417b7f9aeddc857e8060c6211ce8556627c7ae663a0f28fefac2b75934418d12");
    }

    /** 指纹只检查实际旧指导字节；语义效果仍由冻结题目和作品比较判断。 */
    private String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
