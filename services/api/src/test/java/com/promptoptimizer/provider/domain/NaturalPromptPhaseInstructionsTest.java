package com.promptoptimizer.provider.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 冻结旧版本业务指导与候选单变量边界；协议通过不代表真实补全或Plan提问质量已经提升。
 * 哈希取自已关闭第四版真实传输，避免测试和实现一起改变基线而未被察觉。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPromptPhaseInstructionsTest {
    @Test
    void fourthVersionGuidanceStillMatchesTheFrozenRealTransmission() throws Exception {
        // 2026-10-22 single-pass批次的第四版增强及唯一Plan首次请求，按UTF-8原字符串计算。
        assertThat(sha256(NaturalPromptInstructions.enhancementInstruction("4")))
                .isEqualTo("dcef8a376b6f9804a4f15f828beca7eabf3ed739fac396e055560059f255f8d8");
        assertThat(sha256(NaturalPromptInstructions.planningInstruction("4")))
                .isEqualTo("0978f29a6bcfccb4d1de46a0ac549dfb91f0df63c25ad686e5fc9b08fe646a05");
        assertThat(NaturalPromptInstructions.VERSION).isEqualTo("4");
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
    }

    @Test
    void relationalCandidatePreservesProtocolPlanAndCurrentDefault() {
        assertThat(NaturalPromptInstructions.supports("32")).isTrue();
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        assertThat(NaturalPromptInstructions.enhancementInstruction("32")).isNotEqualTo(NaturalPromptInstructions.ENHANCE)
                .startsWith(NaturalPromptInstructions.COMMON).endsWith(NaturalPromptInstructions.TASK_RECORD);
        assertThat(NaturalPromptInstructions.planningInstruction("32")).isEqualTo(NaturalPromptInstructions.planningInstruction("31"));
    }

    @Test
    void supplementCandidateOnlyRegistersANewRenderingVersionWithoutChangingWritingOrPlanGuidance() {
        assertThat(NaturalPromptInstructions.supports("33")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding("33")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview("33")).isTrue();
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        assertThat(NaturalPromptInstructions.enhancementInstruction("33")).isEqualTo(NaturalPromptInstructions.enhancementInstruction("32"));
        assertThat(NaturalPromptInstructions.planningInstruction("33")).isEqualTo(NaturalPromptInstructions.planningInstruction("31"));
    }

    @Test
    void executionBoundaryCandidateKeepsOriginalProtectionAndDoesNotChangeTheDefault() {
        assertThat(NaturalPromptInstructions.supports("34")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding("34")).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview("34")).isTrue();
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        assertThat(NaturalPromptInstructions.enhancementInstruction("34"))
                .isNotEqualTo(NaturalPromptInstructions.enhancementInstruction("31"));
        assertThat(NaturalPromptInstructions.planningInstruction("31")).isEqualTo(NaturalPromptScopedInstructions.PLAN);
        assertThat(NaturalPromptInstructions.planningInstruction("34"))
                .contains("Plan仍须询问执行者不能合理确定", "不将未附资料写成现实缺失");
    }

    @Test
    void phaseProtocolPreservesFourthWritingGuidanceAndDoesNotEnterCriticStages() {
        String fourth = NaturalPromptInstructions.enhancementInstruction("4");
        String businessGuidance = fourth.substring(0, fourth.indexOf("只返回JSON："));
        assertThat(NaturalPromptInstructions.enhancementInstruction("21")).startsWith(businessGuidance);
        for (String version : java.util.List.of("21", "22", "23")) {
            assertThat(NaturalPromptInstructions.supports(version)).isTrue();
            assertThat(NaturalPromptInstructions.requiresTaskUnderstanding(version)).isTrue();
            assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
            assertThat(NaturalPromptFidelityInstructions.supports(version)).isFalse();
        }
        String plan = NaturalPromptInstructions.planningInstruction("4");
        String questionGuidance = plan.substring(0, plan.indexOf("每个问题在taskUnderstanding.openDecisions中记录"));
        String responsibilityGuidance = plan.substring(plan.indexOf("用户独有的实际情况、数值、资源边界或保留参数属于USER_INFORMATION："),
                plan.indexOf("只返回JSON："));
        assertThat(NaturalPromptInstructions.planningInstruction("21")).startsWith(questionGuidance).contains(responsibilityGuidance);
    }

    @Test
    void frameworkGuidanceAndRequiredMarkerAreIndependentOfPlan() {
        String fourth = NaturalPromptInstructions.enhancementInstruction("4");
        String framework = fourth.substring(fourth.indexOf("framework只是正文形成后的组织参考："), fourth.indexOf("只返回JSON："));
        String required = NaturalPromptInstructions.enhancementInstruction("21");
        String optional = NaturalPromptInstructions.enhancementInstruction("22");
        String removed = NaturalPromptInstructions.enhancementInstruction("23");
        assertThat(required).contains(framework);
        assertThat(optional).contains(framework);
        assertThat(removed).isEqualTo(optional.replace(framework, ""));
        assertThat(NaturalPromptInstructions.planningInstruction("22")).isEqualTo(NaturalPromptInstructions.planningInstruction("21"));
        assertThat(NaturalPromptInstructions.planningInstruction("23")).isEqualTo(NaturalPromptInstructions.planningInstruction("21"));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
