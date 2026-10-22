package com.promptoptimizer.provider.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 信息状态与来源原句指导只用于受控25/26候选；冻结既有实际传输与旧协议路由，避免历史再次增强被静默改变。
 * 这些检查只证明版本与调用契约，不证明模型已经理解信息状态或提高真实作品质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptInformationStateInstructionsTest {
    @ParameterizedTest
    @ValueSource(strings = {"4", "20", "21", "22", "23", "24", "25"})
    void earlierLiveGuidanceRemainsByteForByteFrozen(String version) throws Exception {
        // 哈希来自已关闭批次的UTF-8指导和25开发批次实际传输，不由当前候选模板反推。
        var enhancementHashes = Map.of(
                "4", "dcef8a376b6f9804a4f15f828beca7eabf3ed739fac396e055560059f255f8d8",
                "20", "bfed0c2ff4871ce3b671e028db8b749de250eddb89d17704b5443581b7e3cedc",
                "21", "de161a7dbd7a0b0ebc2ee32999e1dcc16fe8cf8748756204347f2683a17f9450",
                "22", "b3d2ca34c9b8e12abbdb2da885082571c2c3f7279ed6ea0a90d1ca4bc3b45ae1",
                "23", "4dc710c8c3220510027f9941993543d5de5c42338d0a4c604c95f5ce6edf85e0",
                "24", "de161a7dbd7a0b0ebc2ee32999e1dcc16fe8cf8748756204347f2683a17f9450",
                "25", "570b6b90868a1b2c8fc5c45ce4e443b20fd4abe3546b5999b3587152d15d00f3");
        assertThat(sha256(NaturalPromptInstructions.enhancementInstruction(version))).isEqualTo(enhancementHashes.get(version));
        assertThat(sha256(NaturalPromptInstructions.planningInstruction(version))).isEqualTo("4".equals(version) || "20".equals(version) || "25".equals(version)
                ? "0978f29a6bcfccb4d1de46a0ac549dfb91f0df63c25ad686e5fc9b08fe646a05"
                : "d602848132f61452fe98e4a6a439c7c98f42ca59f731efaa99cd1db5db6f7e15");
    }

    @ParameterizedTest
    @ValueSource(strings = {"25", "26"})
    void candidateUsesTheExistingReviewProtocolWithoutChangingDefaultOrPlanGuidance(String version) {
        var candidates = Map.of(NaturalPromptInformationStateInstructions.VERSION, NaturalPromptInformationStateInstructions.ENHANCE,
                NaturalPromptSourcePreservingInstructions.VERSION, NaturalPromptSourcePreservingInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.VERSION).isEqualTo("4");
        assertThat(candidates).containsKeys("25", "26");
        assertThat(NaturalPromptInstructions.supports(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
        assertThat(NaturalPromptStageInstructions.supports(version)).isFalse();
        assertThat(NaturalPromptFidelityInstructions.supports(version)).isFalse();
        assertThat(NaturalPromptInstructions.enhancementInstruction(version))
                .isEqualTo(candidates.get(version))
                .isNotEqualTo(NaturalPromptInstructions.enhancementInstruction("4"));
        assertThat(NaturalPromptInstructions.planningInstruction(version))
                .isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
    }

    /** 对原指导内容计算UTF-8哈希；行尾改变也应视为基线变化。 */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
