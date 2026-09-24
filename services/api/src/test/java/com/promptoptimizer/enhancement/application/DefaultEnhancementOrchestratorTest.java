package com.promptoptimizer.enhancement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.api.PlanningContextRequest;
import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.application.BinaryContentExtractor;
import com.promptoptimizer.context.application.DefaultContextAnalyzer;
import com.promptoptimizer.context.application.FileContentSummarizer;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.api.PlanConfirmation;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.policy.application.ConstraintCompleter;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.enhancement.api.OptimizationController;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.history.application.OptimizationHistoryService;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class DefaultEnhancementOrchestratorTest {

    private final DefaultEnhancementOrchestrator orchestrator = new DefaultEnhancementOrchestrator(
            new DefaultContextAnalyzer(
                    new ObjectMapper(),
                    new BinaryContentExtractor(),
                    new FileContentSummarizer()
            ),
            new AmbiguityDetector(),
            new PromptTemplateRegistry(),
            new ConstraintCompleter(),
            new MockPromptEnhancementProvider(),
            TestActors.currentActor(),
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void shouldExposeContextAwareAmbiguitiesThroughDirectEndpointAndSaveHistory() throws Exception {
        var history = mock(OptimizationHistoryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OptimizationController(orchestrator,
                        mock(OptimizationPlanningService.class), history))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/optimizations").contentType(MediaType.APPLICATION_JSON).content("""
                {"rawPrompt":"给用户模块添加登录功能","planConfirmation":null,
                 "context":{"files":[{"path":"src/security/LoginService.java","language":"java",
                   "content":"public void login(HttpServletRequest request) { request.getSession().setAttribute(userId, user); }"}]}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ambiguities").isEmpty())
                .andExpect(jsonPath("$.data.sections[?(@.type == 'CLARIFICATIONS')]").isEmpty())
                .andExpect(jsonPath("$.data.sections[?(@.type == 'CONSTRAINTS')]").isNotEmpty());
        verify(history).save(any(), any());

        mvc.perform(post("/api/v1/optimizations").contentType(MediaType.APPLICATION_JSON).content("""
                {"rawPrompt":"给用户模块添加登录功能","planConfirmation":null,
                 "context":{"customDescription":"Spring Boot 用户服务","files":[]}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ambiguities.length()").value(1))
                .andExpect(jsonPath("$.data.ambiguities[0]").value(org.hamcrest.Matchers.containsString("认证与会话")));
    }

    @Test
    void shouldNotUseDisabledConversationOrProtectedFilesAsBusinessEvidence() {
        var input = new ContextAnalysisRequest("", List.of(new ContextFileInput(
                "private/auth.md", "登录采用 JWT", "markdown")));
        var history = List.of(new ConversationMessage("user", "登录采用 JWT"));
        var policy = new PermissionPolicyInput(List.of("private/auth.md"), List.of());
        var withoutHistory = orchestrator.optimize(new OptimizationRequest("添加登录功能", input,
                new EnhancementOptions(TemplateCode.AUTO, false, true, false), history, policy));
        assertThat(withoutHistory.ambiguities()).hasSize(1);
        assertThat(withoutHistory.contextReport().fileSnippets()).isEmpty();
        var withHistory = orchestrator.optimize(new OptimizationRequest("添加登录功能", input,
                new EnhancementOptions(TemplateCode.AUTO, true, true, false), history, policy));
        assertThat(withHistory.ambiguities()).isEmpty();
    }

    @Test
    void shouldPassRealFileContentAndUseProviderBusinessAssessmentWithoutAnotherPlanningCall() {
        var analyzer = new DefaultContextAnalyzer(new ObjectMapper(), new BinaryContentExtractor(), new FileContentSummarizer());
        var semantic = new DefaultEnhancementOrchestrator(analyzer, new AmbiguityDetector(),
                new PromptTemplateRegistry(), new ConstraintCompleter(), request -> {
                    assertThat(request.context().fileSnippets()).anySatisfy(file ->
                            assertThat(file.content()).contains("PAID", "CANCELLED"));
                    var draft = new MockPromptEnhancementProvider().enhance(request);
                    return new EnhancementProviderResponse(draft.sections(), "test", "semantic", false,
                            List.of("OrderStatus 中 PAID 订单取消后是否需要退款，还是只允许未支付订单取消？"));
                }, TestActors.currentActor(), Clock.systemUTC());
        var result = semantic.optimize(new OptimizationRequest("为订单服务增加取消功能",
                new ContextAnalysisRequest("Spring Boot 订单服务", List.of(new ContextFileInput(
                        "src/OrderStatus.java", "enum OrderStatus { PENDING, PAID, CANCELLED }", "java"))),
                EnhancementOptions.defaults(), List.of(), PermissionPolicyInput.empty()));
        assertThat(result.ambiguities()).containsExactly(
                "OrderStatus 中 PAID 订单取消后是否需要退款，还是只允许未支付订单取消？");
        assertThat(result.provider().model()).isEqualTo("semantic");
    }

    @Test
    void shouldEnhancePromptWithJavaContextAndSafetyConstraints() {
        OptimizationRequest request = new OptimizationRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "这是一个 Spring Boot 用户服务。",
                        List.of(new ContextFileInput("backend/pom.xml", """
                                <project>
                                  <parent>spring-boot</parent>
                                  <dependencies>
                                    <dependency>
                                      <groupId>org.springframework.boot</groupId>
                                      <artifactId>spring-boot-starter-web</artifactId>
                                    </dependency>
                                    <dependency>
                                      <groupId>org.postgresql</groupId>
                                      <artifactId>postgresql</artifactId>
                                    </dependency>
                                  </dependencies>
                                </project>
                                """, "xml"))
                ),
                new EnhancementOptions(TemplateCode.AUTO, true, true, true),
                List.of(),
                new PermissionPolicyInput(List.of("config/prod.yml"), List.of("修改认证策略"))
        );

        OptimizationResult result = orchestrator.optimize(request);

        assertThat(result.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(result.provider().mock()).isTrue();
        assertThat(result.sections()).extracting("type")
                .contains(
                        PromptSectionType.BACKGROUND,
                        PromptSectionType.TASK,
                        PromptSectionType.OUTPUT,
                        PromptSectionType.CONSTRAINTS,
                        PromptSectionType.CLARIFICATIONS,
                        PromptSectionType.ACCEPTANCE,
                        PromptSectionType.EXAMPLES
                );
        assertThat(result.optimizedPrompt())
                .contains("Spring Boot", "Bean Validation", "SQL 注入", "config/prod.yml")
                .doesNotContain("待确认项", "确认下方待确认项");
        assertThat(result.sections()).extracting("type")
                .contains(PromptSectionType.CLARIFICATIONS);
        assertThat(result.contextReport().technologyStack()).extracting("name")
                .contains("Java", "Spring Boot", "PostgreSQL");
    }

    @Test
    void shouldSelectBugFixTemplateWhenPromptDescribesFailure() {
        OptimizationRequest request = new OptimizationRequest(
                "修复登录接口在用户不存在时出现的空指针异常，并补充测试",
                new ContextAnalysisRequest("", List.of()),
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty()
        );

        OptimizationResult result = orchestrator.optimize(request);

        assertThat(result.templateCode()).isEqualTo(TemplateCode.BUG_FIX);
        assertThat(result.optimizedPrompt()).contains("定位根因", "回归测试");
    }

    @Test
    void shouldRejectUnboundLegacyPlanAnswers() {
        OptimizationRequest request = new OptimizationRequest(
                "分析2015-2025年某地区心脑血管疾病死亡率并进行Arriaga分解",
                new ContextAnalysisRequest("公共卫生研究", List.of()),
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty(),
                new PlanConfirmation(List.of(
                        new PlanAnswer("research-region", "这项研究具体覆盖哪个地区？", "广东省"),
                        new PlanAnswer("research-tool", "你希望使用哪种分析工具？", "使用 R 完成分析并提供代码。")
                ))
        );

        assertThatThrownBy(() -> orchestrator.optimize(request))
                .hasMessageContaining("计划编号不能为空");
    }

    @Test
    void shouldRequeryServerSideDocumentsWithConfirmedAnswersBeforeFinalGeneration() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        List<String> analysisQueries = new ArrayList<>();
        ContextAnalyzer contextAnalyzer = new ContextAnalyzer() {
            @Override
            public com.promptoptimizer.context.domain.ContextSnapshot analyze(ContextAnalysisRequest request) {
                return snapshot(request, "");
            }

            @Override
            public com.promptoptimizer.context.domain.ContextSnapshot analyze(
                    ContextAnalysisRequest request,
                    String query
            ) {
                analysisQueries.add(query);
                return snapshot(request, query);
            }

            private com.promptoptimizer.context.domain.ContextSnapshot snapshot(
                    ContextAnalysisRequest request, String query) {
                List<com.promptoptimizer.context.domain.FileSnippet> snippets = query.contains("广东省")
                        ? List.of(
                        new com.promptoptimizer.context.domain.FileSnippet(
                                "研究资料.pdf", "pdf", "研究范围：广东省", "", false),
                        new com.promptoptimizer.context.domain.FileSnippet(
                                "新方案.txt", "text", "研究范围：浙江省", "", false))
                        : List.of();
                return new com.promptoptimizer.context.domain.ContextSnapshot(
                        request.customDescription(),
                        List.of(),
                        List.of(),
                        List.of("研究资料.pdf"),
                        snippets,
                        List.of(),
                        List.of(),
                        "test-v1"
                );
            }
        };
        ProtectedContextFilter contextFilter = new ProtectedContextFilter();
        PlanningSessionService sessions = new PlanningSessionService(
                new InMemoryPlanningSessionStore(clock),
                contextAnalyzer,
                contextFilter,
                TestActors.currentActor(),
                clock
        );
        ContextAnalysisRequest contextRequest = new ContextAnalysisRequest(
                "心脑血管疾病研究",
                List.of(new ContextFileInput(
                        "研究资料.pdf",
                        "",
                        "pdf",
                        "document-123",
                        1_024L
                ))
        );
        String rawPrompt = "分析某地区心脑血管疾病死亡率";
        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                rawPrompt,
                contextRequest,
                PermissionPolicyInput.empty()
        ));
        PlanningContextReference contextReference = new PlanningContextReference(
                preparation.contextId(),
                preparation.version()
        );
        PlanQuestion question = new PlanQuestion(
                "research-region",
                "这项研究具体覆盖哪个地区？",
                "填写实际地区。",
                PlanQuestionType.FREE_TEXT,
                List.of(),
                List.of("广东省"),
                true
        );
        PlanningSessionService.PlanRegistration plan = sessions.registerPlan(
                rawPrompt,
                contextRequest.customDescription(),
                List.of(),
                sessions.resolveForPlan(contextReference, rawPrompt, contextRequest.customDescription()),
                List.of(question)
        );
        DefaultEnhancementOrchestrator contextAwareOrchestrator = new DefaultEnhancementOrchestrator(
                contextAnalyzer,
                new AmbiguityDetector(),
                new PromptTemplateRegistry(),
                new ConstraintCompleter(),
                new MockPromptEnhancementProvider(),
                new OptimizationResultAssembler(),
                contextFilter,
                sessions,
                clock
        );

        OptimizationResult result = contextAwareOrchestrator.optimize(new OptimizationRequest(
                rawPrompt,
                contextRequest,
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty(),
                new PlanConfirmation(
                        plan.planId(),
                        contextReference,
                        List.of(new PlanAnswer(question.id(), question.question(), "广东省"))
                )
        ));

        assertThat(analysisQueries).containsExactly(
                rawPrompt,
                rawPrompt + "\n" + question.question() + "\n广东省"
        );
        assertThat(result.optimizedPrompt()).contains("广东省");
        assertThat(result.ambiguities()).anySatisfy(value -> assertThat(value)
                .contains("研究范围", "研究资料.pdf", "新方案.txt"));
    }
}
