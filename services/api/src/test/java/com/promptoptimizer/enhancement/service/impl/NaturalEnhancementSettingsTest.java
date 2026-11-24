package com.promptoptimizer.enhancement.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** 验证真实Spring配置绑定、缺省回退及非法值启动失败，避免普通入口静默选错策略。 */
class NaturalEnhancementSettingsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(NaturalEnhancementSettings.class);

    @Test
    void expressionGuidanceBindsThroughTheNormalServiceSetting() {
        runner.withPropertyValues("app.enhancement.natural.profile=EXPRESSION").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-expression-v1"));
    }

    @Test
    void projectTaskBindsThroughTheNormalServiceSetting() {
        runner.withPropertyValues("app.enhancement.natural.profile=PROJECT_TASK").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v8"));
    }

    @Test
    void executionDetailsBindsThroughTheNormalServiceSetting() {
        runner.withPropertyValues("app.enhancement.natural.profile=EXECUTION_DETAILS").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("execution-details-v1"));
    }

    @Test
    void currentTaskIsTheDefaultAndV6RemainsAnExplicitRollback() {
        runner.run(context -> assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("execution-details-v2"));
        runner.withPropertyValues("app.enhancement.natural.profile=MATERIAL_HANDOFF_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v6"));
    }

    @Test
    void newRequestsDefaultToTheCurrentTaskGuidance() {
        runner.run(context -> assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("execution-details-v2"));
    }

    @Test
    void stableIsStillAnExplicitRollbackTo31() {
        runner.withPropertyValues("app.enhancement.natural.profile=STABLE").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("31"));
    }

    @Test
    void coreRepairBindsWithoutChangingFrozenInstructionDefaults() {
        runner.withPropertyValues("app.enhancement.natural.profile=CORE_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v1"));
    }

    @Test
    void actionBoundaryRepairRequiresItsOwnExplicitConfiguration() {
        runner.withPropertyValues("app.enhancement.natural.profile=ACTION_BOUNDARY_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v2"));
    }

    @Test
    void arbitraryVersionCannotBeSelectedThroughTheAdoptionSetting() {
        runner.withPropertyValues("app.enhancement.natural.profile=70").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void completionBasisRepairRequiresItsOwnExplicitConfiguration() {
        runner.withPropertyValues("app.enhancement.natural.profile=COMPLETION_BASIS_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v3"));
    }

    @Test
    void simplifiedProtocolIsSelectableWithoutReplacingTheValidatedDefault() {
        runner.withPropertyValues("app.enhancement.natural.profile=SIMPLIFIED").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-simplified-v1"));
    }

    @Test
    void internalDesignCandidateRequiresExplicitConfiguration() {
        runner.withPropertyValues("app.enhancement.natural.profile=INTERNAL_DESIGN").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("internal-design-v1"));
    }

    @Test
    void applicationDefaultUsesV6AndExplicitPlanRepairCanSelectV4() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("profile: ${NATURAL_ENHANCEMENT_PROFILE:CURRENT_TASK}");
        }
        runner.withPropertyValues("app.enhancement.natural.profile=PLAN_CONTEXT_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v4"));
    }

    @Test
    void v5RemainsAnExplicitRollback() {
        runner.withPropertyValues("app.enhancement.natural.profile=PROTOCOL_SCOPE_REPAIR").run(context ->
                assertThat(context.getBean(NaturalEnhancementSettings.class).strategyVersion()).isEqualTo("core-repair-v5"));
    }
}
