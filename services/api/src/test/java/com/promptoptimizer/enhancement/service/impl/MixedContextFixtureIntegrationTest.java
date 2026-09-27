package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;

import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.context.service.impl.BinaryContentExtractor;
import com.promptoptimizer.context.service.impl.DefaultContextAnalyzer;
import com.promptoptimizer.context.service.impl.FileContentSummarizer;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.ConstraintCompleter;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.template.service.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用仓库中的真实样例项目和独立方案文件验证混合上下文的后端分析与生成链路。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MixedContextFixtureIntegrationTest {

    private static final String RAW_PROMPT = "按审批方案在样例项目开发订单接口";
    private static final List<String> FIXTURE_PATHS = List.of(
            "README.md",
            "backend/pom.xml",
            "backend/src/main/java/com/example/demo/DemoApplication.java",
            "backend/src/main/java/com/example/demo/controller/HelloController.java",
            "backend/src/main/resources/application.yml",
            "backend/src/test/java/com/example/demo/DemoApplicationTests.java",
            "frontend/package.json",
            "frontend/src/main.ts",
            "frontend/src/views/App.vue"
    );

    @Test
    void shouldAnalyzeMixedFixtureAndCarryItsContextThroughPlanningAndFinalReport() throws IOException {
        List<ContextFileInput> files = fixtureFiles();
        files.add(new ContextFileInput(
                "审批方案.txt",
                "订单金额超过五万元时须先由财务复核，再交主管批准。",
                "text"
        ));
        ContextAnalysisRequest contextRequest = new ContextAnalysisRequest("", files);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T08:00:00Z"), ZoneOffset.UTC);
        DefaultContextAnalyzer analyzer = new DefaultContextAnalyzer(
                new ObjectMapper(), new BinaryContentExtractor(), new FileContentSummarizer());
        ProtectedContextFilter filter = new ProtectedContextFilterImpl();
        PlanningSessionService sessions = new PlanningSessionServiceImpl(
                new InMemoryPlanningSessionStore(clock), analyzer, filter,
                TestActors.currentActor(), clock);

        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                RAW_PROMPT, contextRequest, PermissionPolicyInput.empty()));

        assertThat(preparation.contextReport().fileSnippets()).extracting(FileSnippet::path)
                .containsAll(FIXTURE_PATHS)
                .contains("审批方案.txt");
        assertThat(preparation.contextReport().technologyStack()).extracting("name")
                .contains("Java", "Spring Boot", "Vue", "TypeScript", "PostgreSQL", "Redis");
        assertThat(preparation.contextReport().dependencies()).extracting("name")
                .contains("org.springframework.boot:spring-boot-starter-web", "vue");
        assertThat(preparation.contextReport().directoryTree())
                .contains("backend/", "frontend/", "审批方案.txt");
        assertThat(preparation.contextReport().analysisStatus()).isEqualTo("COMPLETE");
        assertThat(preparation.contextReport().warnings()).isEmpty();
        assertThat(preparation.digest().warnings()).isEmpty();
        assertThat(preparation.digest().fileSummaries())
                .anySatisfy(summary -> assertThat(summary).contains("审批方案.txt", "五万元", "财务复核"));

        PlanningContextReference reference = new PlanningContextReference(
                preparation.contextId(), preparation.version());
        OptimizationPlanningService planner = new OptimizationPlanningServiceImpl(
                new MockPromptPlanningProvider(), new PromptTemplateRegistryImpl(), sessions, clock);
        OptimizationPlan plan = planner.plan(new OptimizationPlanRequest(
                RAW_PROMPT, "", List.of(), reference));
        assertThat(plan.questions()).extracting("id")
                .doesNotContain("software-environment")
                .contains("software-done");

        List<PlanAnswer> answers = plan.questions().stream()
                .map(question -> new PlanAnswer(
                        question.id(), question.question(), "复核失败时返回 409 状态与失败原因。"))
                .toList();
        DefaultEnhancementOrchestrator orchestrator = new DefaultEnhancementOrchestrator(
                analyzer,
                new AmbiguityDetector(),
                new PromptTemplateRegistryImpl(),
                new ConstraintCompleterImpl(),
                new MockPromptEnhancementProvider(),
                new OptimizationResultAssembler(),
                filter,
                sessions,
                clock
        );
        OptimizationResult result = orchestrator.optimize(new OptimizationRequest(
                RAW_PROMPT,
                contextRequest,
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty(),
                new PlanConfirmation(plan.planId(), reference, answers)
        ));
        assertThat(result.contextReport().fileSnippets()).extracting(FileSnippet::path)
                .contains("backend/pom.xml", "frontend/package.json", "审批方案.txt");
        assertThat(result.optimizedPrompt())
                .contains("Spring Boot", "Vue", "复核失败时返回 409 状态与失败原因",
                        "审批方案.txt", "超过五万元时须先由财务复核");
        assertThat(result.sections()).extracting("type")
                .contains(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                        PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS);
    }

    private static List<ContextFileInput> fixtureFiles() throws IOException {
        Path root = Path.of("..", "..", "test-fixtures", "sample-spring-vue-project");
        List<ContextFileInput> files = new ArrayList<>();
        for (String path : FIXTURE_PATHS) {
            files.add(new ContextFileInput(path, Files.readString(root.resolve(path)), null));
        }
        return files;
    }
}
