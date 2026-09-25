package com.promptoptimizer.enhancement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.api.PlanningContextRequest;
import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.application.BinaryContentExtractor;
import com.promptoptimizer.context.application.DefaultContextAnalyzer;
import com.promptoptimizer.context.application.FileContentSummarizer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.DependencyItem;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.api.PlanConfirmation;
import com.promptoptimizer.enhancement.api.PlanningContextReference;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningSessionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T08:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void shouldFilterProtectedFilesAndExposeOnlyAPlanningDigest() {
        AtomicReference<ContextAnalysisRequest> analyzedRequest = new AtomicReference<>();
        PlanningSessionService service = service(request -> {
            analyzedRequest.set(request);
            return snapshot(request);
        });
        ContextFileInput pom = new ContextFileInput(
                "pom.xml",
                "<artifactId>spring-boot-starter-web</artifactId>",
                "xml"
        );

        PlanningContextPreparation preparation = service.prepareContext(new PlanningContextRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "Spring Boot 用户服务",
                        List.of(pom, new ContextFileInput(".env", "TOKEN=secret-value", "dotenv"))
                ),
                PermissionPolicyInput.empty()
        ));

        assertThat(analyzedRequest.get().files()).extracting(ContextFileInput::path)
                .containsExactly("pom.xml");
        assertThat(preparation.contextId()).isNotBlank();
        assertThat(preparation.version()).startsWith("sha256:");
        assertThat(preparation.digest().technologies()).contains("Spring Boot 3");
        assertThat(preparation.digest().fileSummaries()).containsExactly("pom.xml：Maven 项目配置");
        assertThat(preparation.digest().toString()).doesNotContain("secret-value", ".env");
        assertThat(preparation.contextReport().warnings())
                .contains("已在分析前过滤受保护文件：.env");
    }

    @Test
    void shouldBindAnswersToTheExactPlanAndRestoreServerQuestionCopy() {
        PlanningSessionService service = service(PlanningSessionServiceTest::snapshot);
        PlanningContextPreparation preparation = prepare(service);
        PlanningContextReference reference = reference(preparation);
        List<ConversationMessage> history = List.of(new ConversationMessage("user", "沿用现有认证方式"));
        PlanQuestion question = question();
        PlanningSessionService.PlanRegistration registration = service.registerPlan(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                history,
                service.resolveForPlan(reference, "给用户模块添加登录功能", "Spring Boot 用户服务"),
                List.of(question),
                "tokenhub:kimi-k3"
        );

        PlanningSessionService.ConfirmedPlan confirmed = service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                history,
                new PlanConfirmation(
                        registration.planId(),
                        reference,
                        List.of(new PlanAnswer(question.id(), "客户端伪造的问题", " JWT "))
                )
        );

        assertThat(confirmed.bound()).isTrue();
        assertThat(confirmed.modelId()).isEqualTo("tokenhub:kimi-k3");
        assertThat(confirmed.answers()).containsExactly(new PlanAnswer(
                question.id(),
                question.question(),
                "JWT"
        ));
        assertThatThrownBy(() -> service.confirm(
                "给用户模块添加登录功能",
                "不同的项目描述",
                history,
                new PlanConfirmation(
                        registration.planId(),
                        reference,
                        List.of(new PlanAnswer(question.id(), question.question(), "JWT"))
                )
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("需求内容与确认问题不一致");
    }

    @Test
    void shouldRejectIncompleteAnswersAndReuseOnlyAnIdenticalContextVersion() {
        PlanningSessionService service = service(PlanningSessionServiceTest::snapshot);
        PlanningContextPreparation preparation = prepare(service);
        PlanningContextReference reference = reference(preparation);
        PlanQuestion question = question();
        PlanningSessionService.PlanRegistration registration = service.registerPlan(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                service.resolveForPlan(reference, "给用户模块添加登录功能", "Spring Boot 用户服务"),
                List.of(question)
        );

        assertThatThrownBy(() -> service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                new PlanConfirmation(registration.planId(), reference, List.of())
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("全部问题");

        PlanningSessionService.ConfirmedPlan confirmedWithAnswer = service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                new PlanConfirmation(
                        registration.planId(),
                        reference,
                        List.of(new PlanAnswer(question.id(), question.question(), "JWT"))
                )
        );
        ContextFileInput original = new ContextFileInput("pom.xml", "spring-boot", "xml");
        assertThat(service.reusableContext(
                confirmedWithAnswer,
                new ContextAnalysisRequest("Spring Boot 用户服务", List.of(original)),
                "给用户模块添加登录功能\n登录成功后采用哪种身份保持方式？\nJWT"
        )).isEmpty();

        PlanningSessionService.PlanRegistration noQuestionRegistration = service.registerPlan(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                service.resolveForPlan(reference, "给用户模块添加登录功能", "Spring Boot 用户服务"),
                List.of()
        );
        PlanningSessionService.ConfirmedPlan noQuestionConfirmation = service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                new PlanConfirmation(noQuestionRegistration.planId(), reference, List.of())
        );
        assertThat(service.reusableContext(
                noQuestionConfirmation,
                new ContextAnalysisRequest("Spring Boot 用户服务", List.of(original)),
                "给用户模块添加登录功能"
        )).isPresent();
        assertThat(service.reusableContext(
                noQuestionConfirmation,
                new ContextAnalysisRequest(
                        "Spring Boot 用户服务",
                        List.of(new ContextFileInput("pom.xml", "spring-boot-updated", "xml"))
                ),
                "给用户模块添加登录功能"
        )).isEmpty();
    }

    @Test
    void shouldRejectAChangedContextOwnerOrVersion() {
        PlanningSessionService service = service(PlanningSessionServiceTest::snapshot);
        PlanningContextPreparation preparation = prepare(service);
        PlanningContextReference reference = reference(preparation);

        assertThatThrownBy(() -> service.resolveForPlan(
                reference,
                "给用户模块添加登录功能",
                "已被修改的项目描述"
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("需求内容已变化");
        assertThatThrownBy(() -> service.resolveForPlan(
                new PlanningContextReference(reference.contextId(), "sha256:" + "0".repeat(64)),
                "给用户模块添加登录功能",
                "Spring Boot 用户服务"
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("版本无效");
    }

    @Test
    void shouldRejectContextAndPlanOwnedByAnotherAuthenticatedUser() {
        AtomicReference<UUID> authenticatedUserId = new AtomicReference<>(TestActors.USER_ID);
        PlanningSessionService service = new PlanningSessionService(
                new InMemoryPlanningSessionStore(CLOCK),
                PlanningSessionServiceTest::snapshot,
                new ProtectedContextFilter(),
                () -> TestActors.identity(authenticatedUserId.get()),
                CLOCK
        );
        PlanningContextPreparation preparation = prepare(service);
        PlanningContextReference reference = reference(preparation);
        PlanningSessionService.PlanRegistration registration = service.registerPlan(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                service.resolveForPlan(reference, "给用户模块添加登录功能", "Spring Boot 用户服务"),
                List.of()
        );

        authenticatedUserId.set(UUID.fromString("00000000-0000-0000-0000-000000000202"));

        assertThatThrownBy(() -> service.registerPlan(
                "给用户模块添加登录功能", "Spring Boot 用户服务", List.of(),
                new PlanningSessionService.ResolvedPlanningContext(reference, preparation.digest()), List.of()))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.reusableContext(
                new PlanningSessionService.ConfirmedPlan(List.of(), reference, true),
                new ContextAnalysisRequest("Spring Boot 用户服务", List.of()), "给用户模块添加登录功能"))
                .isInstanceOf(InvalidOptimizationRequestException.class);

        assertThatThrownBy(() -> service.resolveForPlan(
                reference,
                "给用户模块添加登录功能",
                "Spring Boot 用户服务"
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("文件上下文已过期");
        assertThatThrownBy(() -> service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                new PlanConfirmation(registration.planId(), reference, List.of())
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("确认问题已过期");
    }

    @Test
    void shouldAskQuestionsFromTheAnalyzedContextInsteadOfRepeatingKnownFacts() {
        PlanningSessionService sessions = service(PlanningSessionServiceTest::snapshot);
        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "",
                        List.of(new ContextFileInput("pom.xml", "spring-boot", "xml"))
                ),
                PermissionPolicyInput.empty()
        ));
        OptimizationPlanningService planningService = new OptimizationPlanningService(
                new MockPromptPlanningProvider(),
                new PromptTemplateRegistry(),
                sessions,
                CLOCK
        );

        OptimizationPlan plan = planningService.plan(new OptimizationPlanRequest(
                "给用户模块添加登录功能",
                "",
                List.of(),
                reference(preparation)
        ));

        assertThat(plan.questions()).extracting(PlanQuestion::id)
                .contains("software-login-mode", "software-done")
                .doesNotContain("software-environment");
        assertThat(plan.summary()).contains("结合现有项目资料");
        assertThat(plan.planId()).isNotBlank();
        assertThat(plan.planningContext()).isEqualTo(reference(preparation));
        assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-09-14T08:30:00Z"));
    }

    @Test
    void shouldUseResearchFileFactsInsteadOfRepeatingRegionAndDataQuestions() {
        PlanningSessionService sessions = service(request -> new ContextSnapshot(
                request.customDescription(),
                List.of(),
                List.of(),
                List.of("data/广东省死因登记.xlsx"),
                List.of(new FileSnippet(
                        "data/广东省死因登记.xlsx",
                        "xlsx",
                        "year, sex, region, icd10, deaths, population",
                        "广东省疾控中心导出的 Excel 死因登记数据，包含年份、性别、地区、ICD-10、死亡数和人口数。",
                        false
                )),
                List.of(),
                List.of(),
                "test-v1"
        ));
        String rawPrompt = "分析 2015—2025 年某地区心脑血管疾病死亡率并进行 Arriaga 分解";
        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                rawPrompt,
                new ContextAnalysisRequest("", List.of(new ContextFileInput(
                        "data/广东省死因登记.xlsx",
                        "year, sex, region, icd10, deaths, population",
                        "xlsx"
                ))),
                PermissionPolicyInput.empty()
        ));
        OptimizationPlanningService planningService = new OptimizationPlanningService(
                new MockPromptPlanningProvider(),
                new PromptTemplateRegistry(),
                sessions,
                CLOCK
        );

        OptimizationPlan plan = planningService.plan(new OptimizationPlanRequest(
                rawPrompt,
                "",
                List.of(),
                reference(preparation)
        ));

        assertThat(plan.questions()).extracting(PlanQuestion::id)
                .doesNotContain("research-region", "research-data")
                .contains("research-tool", "research-code");
    }

    @Test
    void shouldCarryBusinessRulesFromSolutionDocumentAlongsideProjectStackIntoPlanDigest() {
        String rawPrompt = "按上传的方案文件在现有 Spring Boot 项目实现订单审批";
        PlanningSessionService sessions = service(request -> new ContextSnapshot(
                request.customDescription(),
                List.of(new TechnologyStackItem("Spring Boot 3", "backend/pom.xml", 1)),
                List.of(),
                List.of("backend/pom.xml", "docs/订单审批方案.txt"),
                List.of(
                        new FileSnippet("backend/pom.xml", "xml", "<project />", "Maven 项目配置", false),
                        new FileSnippet("docs/订单审批方案.txt", "text",
                                "订单审批流程：普通订单由部门负责人批准。订单金额超过五万元时，必须先由财务复核，再交主管批准。",
                                "订单审批方案，描述审批流程。", false)
                ),
                List.of(),
                List.of(),
                "test-v1"
        ));
        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                rawPrompt,
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput("backend/pom.xml", "<project />", "xml"),
                        new ContextFileInput("docs/订单审批方案.txt", "订单金额超过五万元时，必须先由财务复核。", "text")
                )),
                PermissionPolicyInput.empty()
        ));

        assertThat(preparation.digest().technologies()).contains("Spring Boot 3");
        assertThat(preparation.digest().fileSummaries())
                .anySatisfy(summary -> assertThat(summary)
                        .contains("订单审批方案.txt", "超过五万元", "财务复核"));
        assertThat(preparation.digest().factCards())
                .anySatisfy(card -> {
                    assertThat(card.origin()).isEqualTo(com.promptoptimizer.enhancement.domain.PlanningFactOrigin.USER_MATERIAL);
                    assertThat(card.sourcePath()).isEqualTo("docs/订单审批方案.txt");
                    assertThat(card.evidence()).contains("必须先由财务复核");
                });
        assertThat(sessions.resolveForPlan(reference(preparation), rawPrompt, "").digest())
                .isEqualTo(preparation.digest());
    }

    @Test
    void shouldAnalyzeMixedCodeAndSolutionFilesBeforeSendingTheirSafeDigestToPlanningProvider() {
        ContextAnalyzer analyzer = new DefaultContextAnalyzer(
                new ObjectMapper(), new BinaryContentExtractor(), new FileContentSummarizer());
        PlanningSessionService sessions = service(analyzer);
        String rawPrompt = "按上传的方案文件在现有项目实现订单审批";
        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                rawPrompt,
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput("backend/pom.xml",
                                "<project><artifactId>spring-boot-starter-parent</artifactId></project>", "xml"),
                        new ContextFileInput("docs/订单审批方案.txt",
                                "审批流程：普通订单由部门负责人批准。订单金额超过五万元时，必须先由财务复核。", "text")
                )),
                PermissionPolicyInput.empty()
        ));
        AtomicReference<PlanningProviderRequest> sent = new AtomicReference<>();
        OptimizationPlanningService planner = new OptimizationPlanningService(request -> {
            sent.set(request);
            return new PlanningProviderResponse("已阅读项目和审批方案。", List.of(), "test", "planner", true);
        }, new PromptTemplateRegistry(), sessions, CLOCK);

        OptimizationPlan plan = planner.plan(new OptimizationPlanRequest(rawPrompt, "", List.of(), reference(preparation)));

        assertThat(preparation.contextReport().fileSnippets()).extracting(FileSnippet::path)
                .contains("backend/pom.xml", "docs/订单审批方案.txt");
        assertThat(sent.get().planningContext().technologies()).contains("Spring Boot");
        assertThat(sent.get().planningContext().fileSummaries())
                .anySatisfy(summary -> assertThat(summary).contains("订单审批方案.txt", "五万元", "财务复核"));
        assertThat(sent.get().planningContext().factCards())
                .anySatisfy(card -> {
                    assertThat(card.sourcePath()).isEqualTo("docs/订单审批方案.txt");
                    assertThat(card.evidence()).contains("必须先由财务复核");
                });
        var confirmed = sessions.confirm(rawPrompt, "", List.of(),
                new PlanConfirmation(plan.planId(), plan.planningContext(), List.of()));
        assertThat(confirmed.planningContextDigest().factCards())
                .containsExactlyElementsOf(sent.get().planningContext().factCards());
    }

    @Test
    void shouldKeepSolutionDocumentWhenManyCodeFilesAppearBeforeItInTheRequest() {
        ContextAnalyzer analyzer = new DefaultContextAnalyzer(
                new ObjectMapper(), new BinaryContentExtractor(), new FileContentSummarizer());
        PlanningSessionService sessions = service(analyzer);
        List<ContextFileInput> files = new ArrayList<>(IntStream.range(0, 45)
                .mapToObj(index -> new ContextFileInput(
                        "src/Module" + index + ".java", "class Module" + index + " {}", "java"))
                .toList());
        files.add(new ContextFileInput("docs/审批方案.txt",
                "订单金额超过五万元时必须由财务复核。", "text"));

        PlanningContextPreparation preparation = sessions.prepareContext(new PlanningContextRequest(
                "按方案实现订单审批", new ContextAnalysisRequest("", files), PermissionPolicyInput.empty()));

        assertThat(preparation.contextReport().fileSnippets()).extracting(FileSnippet::path)
                .contains("docs/审批方案.txt");
        assertThat(preparation.digest().fileSummaries())
                .anySatisfy(summary -> assertThat(summary).contains("审批方案.txt", "财务复核"));
        assertThat(preparation.digest().warnings())
                .anySatisfy(warning -> assertThat(warning).contains("计划摘要仅覆盖", "未覆盖内容不能视为不存在"));
    }

    @Test
    void shouldRejectExpiredContextAndPlanSessions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T08:00:00Z"));
        PlanningSessionService service = new PlanningSessionService(
                new InMemoryPlanningSessionStore(clock),
                PlanningSessionServiceTest::snapshot,
                new ProtectedContextFilter(),
                TestActors.currentActor(),
                clock
        );
        PlanningContextPreparation preparation = service.prepareContext(new PlanningContextRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "Spring Boot 用户服务",
                        List.of(new ContextFileInput("pom.xml", "spring-boot", "xml"))
                ),
                PermissionPolicyInput.empty()
        ));
        PlanningContextReference reference = reference(preparation);
        PlanningSessionService.PlanRegistration registration = service.registerPlan(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                service.resolveForPlan(reference, "给用户模块添加登录功能", "Spring Boot 用户服务"),
                List.of()
        );

        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.resolveForPlan(
                reference,
                "给用户模块添加登录功能",
                "Spring Boot 用户服务"
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("文件上下文已过期");
        assertThatThrownBy(() -> service.confirm(
                "给用户模块添加登录功能",
                "Spring Boot 用户服务",
                List.of(),
                new PlanConfirmation(registration.planId(), reference, List.of())
        ))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("确认问题已过期");
    }

    private static PlanningSessionService service(ContextAnalyzer analyzer) {
        return new PlanningSessionService(
                new InMemoryPlanningSessionStore(CLOCK),
                analyzer,
                new ProtectedContextFilter(),
                TestActors.currentActor(),
                CLOCK
        );
    }

    private static PlanningContextPreparation prepare(PlanningSessionService service) {
        return service.prepareContext(new PlanningContextRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "Spring Boot 用户服务",
                        List.of(new ContextFileInput("pom.xml", "spring-boot", "xml"))
                ),
                PermissionPolicyInput.empty()
        ));
    }

    private static PlanningContextReference reference(PlanningContextPreparation preparation) {
        return new PlanningContextReference(preparation.contextId(), preparation.version());
    }

    private static PlanQuestion question() {
        return new PlanQuestion(
                "software-login-mode",
                "登录成功后采用哪种身份保持方式？",
                "请确认认证方式。",
                PlanQuestionType.FREE_TEXT,
                List.of(),
                List.of("JWT", "Session"),
                true
        );
    }

    private static ContextSnapshot snapshot(ContextAnalysisRequest request) {
        return new ContextSnapshot(
                request.customDescription(),
                List.of(new TechnologyStackItem("Spring Boot 3", "pom.xml", 1)),
                List.of(new DependencyItem("maven", "spring-boot-starter-web", "3.3.13", "pom.xml")),
                List.of("pom.xml"),
                List.of(new FileSnippet(
                        "pom.xml",
                        "xml",
                        "<artifactId>spring-boot-starter-web</artifactId>",
                        "Maven 项目配置",
                        false
                )),
                List.of(),
                List.of(),
                "test-v1"
        );
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
