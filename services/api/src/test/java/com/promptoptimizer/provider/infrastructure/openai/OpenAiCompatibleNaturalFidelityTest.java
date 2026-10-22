package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 在真实Provider边界验证来源约束审阅、局部修补、失败预算及最终回调。
 * 测试只证明阶段与保护契约，语义收益继续由同条件真实作品对照判断。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalFidelityTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String ORIGINAL_BODY = "请写邮件，发布时间为周五。";
    private static final String CORRECTED_BODY = "请写邮件，发布时间尚未确定。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-api-key");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void noIssuePreservesTheSameDraftAndBoundSourcesWhileFinalizingAfterReview() throws Exception {
        String body = "请写邮件。\n\n发布时间尚未确定。";
        var callbacks = new AtomicInteger();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(body, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertThat(callbacks.get()).isEqualTo(1);
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("fidelityDraft").size()).isEqualTo(3);
            assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
            assertThat(payload.path("fidelityDraft").has("framework")).isTrue();
            assertThat(payload.path("fidelityDraft").has("taskUnderstanding")).isTrue();
            assertThat(payload.path("fidelityDraft").has("provider")).isFalse();
            assertThat(payload.has("completionSuggestions")).isFalse();
        }).andRespond(withSuccess(response(noIssues()), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 71), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(input("17"), draft -> {
                callbacks.incrementAndGet();
                firstDraft.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(result).isSameAs(firstDraft.get());
        assertThat(result.optimizedPrompt()).isEqualTo(body);
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(observations).hasSize(2).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void preparationGenerationAndFidelityUseThreeDistinctCallsWithoutNewSources() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.has("completionSuggestions")).isFalse();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(json.writeValueAsString(Map.of("taskUnderstanding", task(true, null)))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("completionSuggestions").isObject()).isTrue();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(draftResponse(CORRECTED_BODY, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(CORRECTED_BODY);
        }).andRespond(withSuccess(response(noIssues()), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input("16"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        server.verify();
    }

    @Test
    void rejectedFirstDraftUsesItsOwnBoundedRepairAndNeverReachesFidelity() throws Exception {
        String first = response(draftResponse(CORRECTED_BODY, "发布时间尚未确定"));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhance(input("17"), draft -> {
            callbacks.incrementAndGet();
            throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "首稿未通过必要保护", false);
        })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isEqualTo(2);
        server.verify();
    }

    @Test
    void sourceGroundedPatchReceivesFreshCoverageAndTheFinalValidationCallback() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        var seenBodies = new ArrayList<String>();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        var result = provider.enhance(input("17"), draft -> {
            seenBodies.add(draft.optimizedPrompt());
            firstDraft.compareAndSet(null, draft);
            var resolved = draft.taskUnderstanding().resolve(input("17"));
            resolved.review().validateEnhancement(resolved, draft.optimizedPrompt());
            return draft;
        });
        assertThat(seenBodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        assertThat(result.framework()).isEqualTo(firstDraft.get().framework());
        assertThat(result.provider()).isSameAs(firstDraft.get().provider());
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().review().requirementCoverage().get(1).quotes())
                .containsExactly("发布时间尚未确定");
        server.verify();
    }

    @Test
    void invalidFidelityPatchRetriesOnlyTheReviewAndCountsAllThreeAttempts() throws Exception {
        String first = response(draftResponse(ORIGINAL_BODY, "发布时间为周五"));
        String invalid = response(patch("fidelityDraft", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定"));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> {
                JsonNode payload = taskInput(request);
                assertBoundSources(payload);
                assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(ORIGINAL_BODY);
            }).andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        var observations = new ArrayList<ModelCallObservation>();
        var callbacks = new AtomicInteger();
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 72), (tenant, fact) -> observations.add(fact)))) {
            assertThatThrownBy(() -> provider.enhance(input("17"), draft -> {
                callbacks.incrementAndGet();
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            })).isInstanceOf(ProviderException.class);
        }
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(observations).hasSize(3).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        assertThat(observations.stream().filter(ModelCallObservation::successful).count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void planUsesFourthVersionInstructionAndOneCallWithoutFidelity() throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input("17").context(), List.of(), List.of(), List.of(), null)
                .withStrategyVersion("17");
        var planTask = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        String plan = json.writeValueAsString(Map.of("summary", "任务充分", "questions", List.of(), "taskUnderstanding", planTask));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
            assertThat(taskInput(request).has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(plan), MediaType.APPLICATION_JSON));
        var result = provider.plan(planInput, value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(result.questions()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("fidelityDraft", "taskUnderstanding");
        server.verify();
    }

    @Test
    void version18DetectsWithoutSelfClassificationAndPreservesANoIssueDraft() throws Exception {
        var callbacks = new AtomicInteger();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("messages").get(0).path("content").asText())
                    .isEqualTo(NaturalPromptInstructions.enhancementInstruction("4"));
            assertBoundSources(taskInput(request));
            assertThat(taskInput(request).has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(draftResponse(CORRECTED_BODY, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertThat(callbacks.get()).isEqualTo(1);
            assertDetectionInput(taskInput(request), CORRECTED_BODY);
        }).andRespond(withSuccess(response(noFindings()), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 81), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(input("18"), draft -> {
                callbacks.incrementAndGet();
                firstDraft.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(result).isSameAs(firstDraft.get());
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(observations).hasSize(2).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void version18RepairsOnlyDetectedProblemsInASeparateThirdCall() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertRepairInput(taskInput(request)))
                .andRespond(withSuccess(response(patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        var bodies = new ArrayList<String>();
        var observations = new ArrayList<ModelCallObservation>();
        var initial = new AtomicReference<NaturalPromptDraft>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 82), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(input("18"), draft -> {
                bodies.add(draft.optimizedPrompt());
                initial.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(result.provider()).isSameAs(initial.get().provider());
        assertThat(result.framework()).isSameAs(initial.get().framework());
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().review().requirementCoverage().get(1).quotes())
                .containsExactly("发布时间尚未确定");
        assertThat(observations).hasSize(3).allSatisfy(fact -> assertThat(fact.successful()).isTrue());
        server.verify();
    }

    @Test
    void version18RetriesAnInvalidDetectionWithoutRegeneratingOrStartingRepairEarly() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(findings("fidelityDraft", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertRepairInput(taskInput(request)))
                .andRespond(withSuccess(response(patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 83), (tenant, fact) -> observations.add(fact)))) {
            var result = provider.enhance(input("18"), draft -> {
                callbacks.incrementAndGet();
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
            assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        }
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(observations).hasSize(4).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        assertThat(observations.stream().filter(ModelCallObservation::successful).count()).isEqualTo(3);
        server.verify();
    }

    @Test
    void version18CannotAddProblemsDuringRepairAndRetriesOnlyTheRepairStage() throws Exception {
        String valid = patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定");
        ObjectNode expanded = (ObjectNode) json.readTree(valid);
        expanded.withArray("issues").add(json.valueToTree(Map.of("type", "REQUIREMENT_OMISSION", "sourceId", "rawPrompt",
                "sourceQuote", "写邮件", "bodyQuote", "请写邮件", "replacement", "请写一封完整邮件")));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertRepairInput(taskInput(request)))
                .andRespond(withSuccess(response(json.writeValueAsString(expanded)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertRepairInput(taskInput(request)))
                .andRespond(withSuccess(response(valid), MediaType.APPLICATION_JSON));
        var bodies = new ArrayList<String>();
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 84), (tenant, fact) -> observations.add(fact)))) {
            var result = provider.enhance(input("18"), draft -> {
                bodies.add(draft.optimizedPrompt());
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
            assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        }
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(observations).hasSize(4);
        assertThat(observations.stream().filter(ModelCallObservation::successful).count()).isEqualTo(3);
        server.verify();
    }

    @Test
    void version18ExhaustedDetectionFailsWithoutTreatingInvalidFindingsAsNoIssues() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                    .andRespond(withSuccess(response(findings("actualAnswers.0", "用户已经确认周五。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        }
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhance(input("18"), draft -> {
            callbacks.incrementAndGet();
            return draft;
        })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isEqualTo(1);
        server.verify();
    }

    @Test
    void version18RejectsTheFirstDraftBeforeDetectionWhenExistingProtectionFails() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> {
                assertThat(taskInput(request).has("fidelityDraft")).isFalse();
                assertThat(taskInput(request).has("fidelityFindings")).isFalse();
            }).andRespond(withSuccess(response(draftResponse(CORRECTED_BODY, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        }
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhance(input("18"), draft -> {
            callbacks.incrementAndGet();
            throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "首稿未通过必要保护", false);
        })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isEqualTo(2);
        server.verify();
    }

    @Test
    void version18FinalValidationCanRejectARepairedDraftWithoutRestartingDetection() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> assertRepairInput(taskInput(request)))
                    .andRespond(withSuccess(response(patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        }
        var bodies = new ArrayList<String>();
        assertThatThrownBy(() -> provider.enhance(input("18"), draft -> {
            bodies.add(draft.optimizedPrompt());
            if (CORRECTED_BODY.equals(draft.optimizedPrompt())) {
                throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "最终正文未通过保护", false);
            }
            return draft;
        })).isInstanceOf(ProviderException.class);
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY, CORRECTED_BODY);
        server.verify();
    }

    @Test
    void version18PlanUsesFourthVersionAndNeverStartsDetectionOrRepair() throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input("18").context(), List.of(), List.of(), List.of(), null)
                .withStrategyVersion("18");
        var planTask = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        String plan = json.writeValueAsString(Map.of("summary", "任务充分", "questions", List.of(), "taskUnderstanding", planTask));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("messages").get(0).path("content").asText())
                    .isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
            assertThat(taskInput(request).has("fidelityDraft")).isFalse();
            assertThat(taskInput(request).has("fidelityFindings")).isFalse();
        }).andRespond(withSuccess(response(plan), MediaType.APPLICATION_JSON));
        var result = provider.plan(planInput, value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(result.questions()).isEmpty();
        server.verify();
    }

    @Test
    void version19NoIssuesUsesTwoCallsAndKeepsTheSameValidatedDraft() throws Exception {
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        var callbacks = new AtomicInteger();
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.enhancementInstruction("4"));
            assertBoundSources(taskInput(request));
            assertThat(taskInput(request).has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(draftResponse(CORRECTED_BODY, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptFidelityInstructions.GROUNDED_DETECT);
            assertDetectionInput(taskInput(request), CORRECTED_BODY);
            assertThat(taskInput(request).has("fidelityEditTargets")).isFalse();
            assertThat(callbacks.get()).isEqualTo(1);
        }).andRespond(withSuccess(response(noFindings()), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 91), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(input("19"), draft -> {
                callbacks.incrementAndGet();
                firstDraft.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(result).isSameAs(firstDraft.get());
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(observations).hasSize(2).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void version19KeepsTwoAuthorityDiagnosesAndTheirSourcesButEditsTheirSharedSpanOnce() throws Exception {
        var bound = authorityInput();
        // 首稿解析沿用既有 strip 语义；保真修订须保留正文内部的换行与未命中内容。
        String body = "请核查近期修改是否已部署。\r\n" + authorityProblem() + "\n尾部";
        String replacement = "本次只核查，不执行部署；生产环境也不得操作。";
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(authorityDraft(body)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptFidelityInstructions.GROUNDED_DETECT);
            assertAuthoritySources(taskInput(request));
            assertThat(taskInput(request).path("fidelityDraft").size()).isEqualTo(2);
            assertThat(taskInput(request).path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
            assertThat(taskInput(request).path("fidelityDraft").has("taskUnderstanding")).isFalse();
            assertThat(taskInput(request).has("fidelityFindings")).isFalse();
            assertThat(taskInput(request).has("fidelityEditTargets")).isFalse();
        }).andRespond(withSuccess(response(authorityFindings()), MediaType.APPLICATION_JSON));
        JsonNode expectedAuthorityFindings = json.readTree(authorityFindings());
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptFidelityInstructions.GROUNDED_PATCH);
            JsonNode payload = taskInput(request);
            assertAuthoritySources(payload);
            assertThat(payload.path("fidelityDraft").size()).isEqualTo(3);
            assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
            assertThat(payload.path("fidelityDraft").path("taskUnderstanding").path("requirements")).hasSize(2);
            assertThat(payload.path("fidelityFindings")).isEqualTo(expectedAuthorityFindings);
            assertOneGroupedTarget(payload, authorityProblem(), List.of(0, 1));
        }).andRespond(withSuccess(response(groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", replacement)),
                authorityTask("生产环境也不得操作"))), MediaType.APPLICATION_JSON));
        var bodies = new ArrayList<String>();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 92), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(bound, draft -> {
                bodies.add(draft.optimizedPrompt());
                firstDraft.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        String corrected = "请核查近期修改是否已部署。\r\n" + replacement + "\n尾部";
        assertThat(bodies).containsExactly(body, corrected);
        assertThat(result.optimizedPrompt()).isEqualTo(corrected);
        assertThat(result.provider()).isSameAs(firstDraft.get().provider());
        assertThat(result.framework()).isSameAs(firstDraft.get().framework());
        assertThat(result.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
        assertThat(observations).hasSize(3).allSatisfy(fact -> assertThat(fact.successful()).isTrue());
        server.verify();
    }

    @Test
    void version19OneScopeSpanCanRetainBothExpansionAndCoreOmissionDiagnoses() throws Exception {
        String raw = "帮我设计一个最简单的AA分账小工具，输入总金额和人数，算出每人应付；输入和展示方式由你设计。";
        String body = "AA分账小工具必须支持将消费项目分组，并按组分别计算。";
        var simpleInput = new NaturalPromptInput(raw, input("19").context(), List.of(), List.of(), List.of(), null)
                .withStrategyVersion("19");
        var draftTask = scopeTask(raw, body);
        String detected = json.writeValueAsString(Map.of("issues", List.of(
                Map.of("type", "SCOPE_EXPANSION", "sourceId", "rawPrompt", "sourceQuote", raw, "bodyQuote", body,
                        "effect", "最简单的总额均分任务被要求实现逐项分组消费"),
                Map.of("type", "REQUIREMENT_OMISSION", "sourceId", "rawPrompt", "sourceQuote", "输入总金额和人数，算出每人应付",
                        "bodyQuote", body, "effect", "首稿未保留总额和人数输入及每人应付这一核心交付"))));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(json.writeValueAsString(Map.of(
                "optimizedPrompt", body, "framework", "APE", "taskUnderstanding", draftTask))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptFidelityInstructions.GROUNDED_DETECT);
            assertThat(taskInput(request).path("taskSources")).hasSize(1);
        }).andRespond(withSuccess(response(detected), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = taskInput(request);
            assertThat(payload.path("fidelityFindings")).isEqualTo(json.readTree(detected));
            assertOneGroupedTarget(payload, body, List.of(0, 1));
        }).andRespond(withSuccess(response(groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", raw)),
                scopeTask(raw, raw))), MediaType.APPLICATION_JSON));
        var result = provider.enhance(simpleInput, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(raw);
        server.verify();
    }

    @Test
    void version19PartialOverlapsRetryOnlyDetectionWithOriginalSourcesAndBody() throws Exception {
        String overlap = json.writeValueAsString(Map.of("issues", List.of(
                Map.of("type", "UNSUPPORTED_FACT", "sourceId", "actualAnswers.0", "sourceQuote", "发布时间还没定。",
                        "bodyQuote", "发布时间为周五", "effect", "未知时间被写成周五"),
                Map.of("type", "STATE_PROMOTION", "sourceId", "rawPrompt", "sourceQuote", "写邮件",
                        "bodyQuote", "发布时间为", "effect", "在原定任务上新增已确定时间"))));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertDetectionInput(taskInput(request), ORIGINAL_BODY))
                .andRespond(withSuccess(response(overlap), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertDetectionInput(taskInput(request), ORIGINAL_BODY);
            assertThat(taskInput(request).has("fidelityEditTargets")).isFalse();
        }).andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertBoundSources(taskInput(request));
            assertOneGroupedTarget(taskInput(request), "发布时间为周五", List.of(0));
        }).andRespond(withSuccess(response(groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", "发布时间尚未确定")),
                task(false, "发布时间尚未确定"))), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        var bodies = new ArrayList<String>();
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 93), (tenant, fact) -> observations.add(fact)))) {
            var result = provider.enhance(input("19"), draft -> {
                bodies.add(draft.optimizedPrompt());
                return draft;
            });
            assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        }
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(observations).hasSize(4).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        assertThat(observations.stream().filter(ModelCallObservation::successful).count()).isEqualTo(3);
        server.verify();
    }

    @Test
    void version19ExhaustedPartialOverlapFailsWithoutStartingRepair() throws Exception {
        String overlap = json.writeValueAsString(Map.of("issues", List.of(
                Map.of("type", "UNSUPPORTED_FACT", "sourceId", "actualAnswers.0", "sourceQuote", "发布时间还没定。",
                        "bodyQuote", "发布时间为周五", "effect", "未知时间被写成周五"),
                Map.of("type", "STATE_PROMOTION", "sourceId", "actualAnswers.0", "sourceQuote", "发布时间还没定。",
                        "bodyQuote", "发布时间为", "effect", "同一状态的局部重叠诊断"))));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> {
                assertDetectionInput(taskInput(request), ORIGINAL_BODY);
                assertThat(taskInput(request).has("fidelityEditTargets")).isFalse();
            }).andRespond(withSuccess(response(overlap), MediaType.APPLICATION_JSON));
        }
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhance(input("19"), draft -> {
            callbacks.incrementAndGet();
            return draft;
        })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isEqualTo(1);
        server.verify();
    }

    @Test
    void version19CannotAddAnUnreviewedTargetAndRetriesOnlyThePatch() throws Exception {
        String valid = groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", "发布时间尚未确定")),
                task(false, "发布时间尚未确定"));
        String expanded = groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", "发布时间尚未确定"),
                Map.of("targetIndex", 1, "replacement", "添加未经检测的编辑")), task(false, "发布时间尚未确定"));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertOneGroupedTarget(taskInput(request), "发布时间为周五", List.of(0));
            assertBoundSources(taskInput(request));
        }).andRespond(withSuccess(response(expanded), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertOneGroupedTarget(taskInput(request), "发布时间为周五", List.of(0));
            assertBoundSources(taskInput(request));
        }).andRespond(withSuccess(response(valid), MediaType.APPLICATION_JSON));
        var bodies = new ArrayList<String>();
        var result = provider.enhance(input("19"), draft -> {
            bodies.add(draft.optimizedPrompt());
            return draft;
        });
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        server.verify();
    }

    @Test
    void version19GroupedPatchCannotLoseABoundAnswerWhenRebuildingCoverage() throws Exception {
        var missingAnswer = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件"))), List.of(), List.of()));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(findings("actualAnswers.0", "发布时间还没定。", "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> assertBoundSources(taskInput(request)))
                .andRespond(withSuccess(response(groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", "发布时间尚未确定")),
                        missingAnswer)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertBoundSources(taskInput(request));
            assertOneGroupedTarget(taskInput(request), "发布时间为周五", List.of(0));
        }).andRespond(withSuccess(response(groupedRepair(List.of(Map.of("targetIndex", 0, "replacement", "发布时间尚未确定")),
                task(false, "发布时间尚未确定"))), MediaType.APPLICATION_JSON));
        var bodies = new ArrayList<String>();
        var result = provider.enhance(input("19"), draft -> {
            bodies.add(draft.optimizedPrompt());
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(bodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().requirements().get(1).evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
        server.verify();
    }

    @Test
    void version19PlanStillUsesOneFourthVersionCallWithoutFidelityStages() throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input("19").context(), List.of(), List.of(), List.of(), null)
                .withStrategyVersion("19");
        var planTask = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        String plan = json.writeValueAsString(Map.of("summary", "任务充分", "questions", List.of(), "taskUnderstanding", planTask));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertSystem(request, NaturalPromptInstructions.planningInstruction("4"));
            var payload = taskInput(request);
            assertThat(payload.has("fidelityDraft")).isFalse();
            assertThat(payload.has("fidelityFindings")).isFalse();
            assertThat(payload.has("fidelityEditTargets")).isFalse();
        }).andRespond(withSuccess(response(plan), MediaType.APPLICATION_JSON));
        var result = provider.plan(planInput, value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(result.questions()).isEmpty();
        server.verify();
    }

    /** 同片段诊断全部保留，服务端只生成一个可编辑目标，模型不能另造首稿范围。 */
    private void assertOneGroupedTarget(JsonNode payload, String bodyQuote, List<Integer> diagnoses) {
        assertThat(payload.path("fidelityEditTargets")).hasSize(1);
        var target = payload.path("fidelityEditTargets").get(0);
        assertThat(target.path("index").asInt()).isZero();
        assertThat(target.path("bodyQuote").asText()).isEqualTo(bodyQuote);
        assertThat(target.path("diagnosisIndices")).isEqualTo(json.valueToTree(diagnoses));
    }

    private void assertSystem(org.springframework.http.client.ClientHttpRequest request, String expected) throws java.io.IOException {
        var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo(expected);
    }

    /** 原始核查请求、实际回答与未确认助手建议保持各自来源，任何辅助字段都不能替代它们。 */
    private void assertAuthoritySources(JsonNode payload) {
        assertThat(payload.path("rawPrompt").asText()).isEqualTo(authorityRaw());
        assertThat(payload.path("actualAnswers").get(0).path("answer").asText()).isEqualTo(authorityAnswer());
        assertThat(payload.path("conversation").get(0).path("content").asText()).isEqualTo(authoritySuggestion());
        var kinds = new LinkedHashMap<String, String>();
        payload.path("taskSources").forEach(source -> kinds.put(source.path("id").asText(), source.path("kind").asText()));
        assertThat(kinds).containsExactlyInAnyOrderEntriesOf(Map.of("rawPrompt", "ORIGINAL_REQUEST",
                "actualAnswers.0", "BOUND_PLAN_ANSWER", "conversation.0", "ASSISTANT_CONVERSATION"));
    }

    private String authorityRaw() { return "请核查近期修改是否已部署，说明依据和差距；本次只核查，不执行部署。"; }
    private String authorityAnswer() { return "本次不允许部署，生产环境也不得操作。"; }
    private String authoritySuggestion() { return "建议先部署后验证，但这只是助手建议，用户尚未确认。"; }
    private String authorityProblem() { return "用户已确认先把近期修改部署到生产环境，再检查是否生效。"; }

    private NaturalPromptInput authorityInput() {
        return new NaturalPromptInput(authorityRaw(), input("19").context(),
                List.of(new PlanAnswer("q_auth", "允许哪些部署操作？", authorityAnswer())),
                List.of(new ConversationMessage("assistant", authoritySuggestion())), List.of(), null).withStrategyVersion("19");
    }

    private NaturalTaskUnderstanding authorityTask(String answerQuote) {
        var requirements = List.of(new NaturalTaskUnderstanding.Requirement("核查修改部署状态",
                        List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "请核查近期修改是否已部署")),
                        List.of(NaturalTaskUnderstanding.Dimension.GOAL)),
                new NaturalTaskUnderstanding.Requirement("实际回答禁止部署",
                        List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", authorityAnswer())),
                        List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
        return new NaturalTaskUnderstanding("核查部署状态", "依据和差距", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of("请核查近期修改是否已部署")),
                                new NaturalTaskReview.Coverage(1, List.of(answerQuote))), List.of(), List.of()));
    }

    private String authorityDraft(String body) throws Exception {
        return json.writeValueAsString(Map.of("optimizedPrompt", body, "framework", "APE",
                "taskUnderstanding", authorityTask("用户已确认")));
    }

    /** 不同来源揭示同一原句的越权与状态提升，两条诊断不能因归组而丢失。 */
    private String authorityFindings() throws Exception {
        return json.writeValueAsString(Map.of("issues", List.of(
                Map.of("type", "AUTHORITY_EXPANSION", "sourceId", "rawPrompt", "sourceQuote", "本次只核查，不执行部署",
                        "bodyQuote", authorityProblem(), "effect", "只核查被改成先执行生产部署"),
                Map.of("type", "STATE_PROMOTION", "sourceId", "conversation.0", "sourceQuote", authoritySuggestion(),
                        "bodyQuote", authorityProblem(), "effect", "未确认助手建议被写成用户已确认"))));
    }

    private NaturalTaskUnderstanding scopeTask(String raw, String anchor) {
        var goal = new NaturalTaskUnderstanding.Requirement("设计最简单的总额均分小工具",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", raw)), List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        return new NaturalTaskUnderstanding("设计AA分账工具", "工具设计", List.of(goal), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                        List.of(new NaturalTaskReview.Coverage(0, List.of(anchor))), List.of(), List.of()));
    }

    /** 修订只引用服务端目标编号，最终记录仍由完整原始输入与正文保护校验。 */
    private String groupedRepair(List<Map<String, Object>> edits, NaturalTaskUnderstanding understanding) throws Exception {
        return json.writeValueAsString(Map.of("edits", edits, "taskUnderstanding", understanding));
    }

    /** 检测只看完整原来源及首稿正文，不能从首稿的自分类记录取得结论。 */
    private void assertDetectionInput(JsonNode payload, String body) {
        assertBoundSources(payload);
        assertThat(payload.path("fidelityDraft").size()).isEqualTo(2);
        assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
        assertThat(payload.path("fidelityDraft").path("framework").asText()).isEqualTo("APE");
        assertThat(payload.path("fidelityDraft").has("taskUnderstanding")).isFalse();
        assertThat(payload.has("fidelityFindings")).isFalse();
        assertThat(payload.has("completionSuggestions")).isFalse();
    }

    /** 修订接收已冻结的问题和旧记录用于更新覆盖，不允许将它们添加为事实来源。 */
    private void assertRepairInput(JsonNode payload) {
        assertBoundSources(payload);
        assertThat(payload.path("fidelityDraft").size()).isEqualTo(3);
        assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(ORIGINAL_BODY);
        assertThat(payload.path("fidelityDraft").path("taskUnderstanding").path("requirements")).hasSize(2);
        assertThat(payload.path("fidelityFindings").path("issues")).hasSize(1);
        var finding = payload.path("fidelityFindings").path("issues").get(0);
        assertThat(finding.path("type").asText()).isEqualTo("UNSUPPORTED_FACT");
        assertThat(finding.path("sourceId").asText()).isEqualTo("actualAnswers.0");
        assertThat(finding.path("sourceQuote").asText()).isEqualTo("发布时间还没定。");
        assertThat(finding.path("bodyQuote").asText()).isEqualTo("发布时间为周五");
        assertThat(finding.path("effect").asText()).isNotBlank();
        assertThat(finding.has("replacement")).isFalse();
        assertThat(payload.has("completionSuggestions")).isFalse();
    }

    /** 检测响应不包含替代正文或任务记录，保证没有问题时不进入重写。 */
    private String noFindings() throws Exception {
        return json.writeValueAsString(Map.of("issues", List.of()));
    }

    /** 问题描述定位原依据与正文，并说明实质影响，暂不选择修订文本。 */
    private String findings(String sourceId, String sourceQuote, String bodyQuote) throws Exception {
        return json.writeValueAsString(Map.of("issues", List.of(Map.of("type", "UNSUPPORTED_FACT", "sourceId", sourceId,
                "sourceQuote", sourceQuote, "bodyQuote", bodyQuote, "effect", "把真实回答中仍未确定的发布时间写成周五，会改变邮件事实。"))));
    }

    /** 验证审阅辅助字段不能替代原始需求、绑定题答或服务端来源目录。 */
    private void assertBoundSources(JsonNode payload) {
        assertThat(payload.path("rawPrompt").asText()).isEqualTo("写邮件");
        assertThat(payload.path("actualAnswers")).hasSize(1);
        assertThat(payload.path("actualAnswers").get(0).path("answer").asText()).isEqualTo("发布时间还没定。");
        assertThat(payload.path("taskSources")).hasSize(2);
        var ids = new ArrayList<String>();
        payload.path("taskSources").forEach(source -> ids.add(source.path("id").asText()));
        assertThat(ids).containsExactly("rawPrompt", "actualAnswers.0");
    }

    /** 只解析发送给模型的业务JSON，不读取请求认证头。 */
    private JsonNode taskInput(org.springframework.http.client.ClientHttpRequest request) throws java.io.IOException {
        var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        String user = sent.path("messages").get(1).path("content").asText();
        return json.readTree(user.substring(user.indexOf('{')));
    }

    /** 实际回答由既有服务端绑定入口提供，测试不得将模型建议作为用户答复。 */
    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q_time", "发布时间是什么？", "发布时间还没定。")), List.of(), List.of(), null)
                .withStrategyVersion(version);
    }

    /** 两项要求分别关联原始需求和仍未知的实际答复，最终覆盖只跟随正文变化。 */
    private NaturalTaskUnderstanding task(boolean preparation, String confirmationQuote) {
        return new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement(),
                new NaturalTaskUnderstanding.Requirement("发布时间以实际回答为准",
                        List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "发布时间还没定。")),
                        List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION))), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION,
                        preparation ? List.of() : List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件")),
                                new NaturalTaskReview.Coverage(1, List.of(confirmationQuote))), List.of(), List.of()));
    }

    /** 保留明确的原始任务来源，供直接增强与零问Plan共同使用。 */
    private NaturalTaskUnderstanding.Requirement goalRequirement() {
        return new NaturalTaskUnderstanding.Requirement("写邮件",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")), List.of(NaturalTaskUnderstanding.Dimension.GOAL));
    }

    /** 首稿及补丁稿都按既有增强协议构造，错误事实也必须具有合法来源与正文引用才进入语义检查。 */
    private String draftResponse(String body, String confirmationQuote) throws Exception {
        return json.writeValueAsString(Map.of("optimizedPrompt", body, "framework", "APE", "taskUnderstanding", task(false, confirmationQuote)));
    }

    /** 无问题响应不提供替代稿或重新抽取任务，确保不会凭空改写已经有效的内容。 */
    private String noIssues() throws Exception {
        ObjectNode result = json.createObjectNode();
        result.putArray("issues");
        result.putNull("taskUnderstanding");
        return json.writeValueAsString(result);
    }

    /** 修补仅给真实来源及首稿中的唯一局部原句，重新填写最终正文的覆盖记录。 */
    private String patch(String sourceId, String sourceQuote, String bodyQuote, String replacement) throws Exception {
        return json.writeValueAsString(Map.of("issues", List.of(Map.of("type", "UNSUPPORTED_FACT",
                "sourceId", sourceId, "sourceQuote", sourceQuote, "bodyQuote", bodyQuote, "replacement", replacement)),
                "taskUnderstanding", task(false, "发布时间尚未确定")));
    }

    /** 每次模拟调用携带用量，包含最终拒绝的审阅，验证它们仍进入实际调用账目。 */
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
