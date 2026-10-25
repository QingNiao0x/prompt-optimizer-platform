package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import static com.promptoptimizer.analytics.domain.AnalyticsEventType.*;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立验证模式、服务端原题绑定、付费操作幂等、正文修订与完整身份隔离，不调用外部模型。 */
class ImagePromptServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CurrentActor current = mock(CurrentActor.class);
    private final ImagePromptAssetService assets = mock(ImagePromptAssetService.class);
    private final ImagePromptModelService models = mock(ImagePromptModelService.class);
    private final ImagePromptProvider provider = mock(ImagePromptProvider.class);
    private final AnalyticsEventService analytics = mock(AnalyticsEventService.class);
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    private ImagePromptHistoryService history;
    private ImagePromptService service;

    @BeforeEach
    void setup() {
        var beans = new StaticListableBeanFactory();
        history = new ImagePromptHistoryService(beans.getBeanProvider(com.promptoptimizer.imageprompt.mapper.ImagePromptMapper.class), json, false);
        service = new ImagePromptService(current, assets, models, provider,
                new ImagePromptStateStore(beans.getBeanProvider(StringRedisTemplate.class), json, "MEMORY"), history, json, analytics);
        when(current.require()).thenReturn(actor);
    }

    @Test
    void referenceModeCannotSilentlyBecomeCreationOrRunWithoutImage() {
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), task(Mode.REFERENCE_RECONSTRUCTION, List.of(), "", ""))))
                .isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), task(Mode.REFERENCE_RECONSTRUCTION,
                List.of(new Reference(UUID.randomUUID(), ImageRole.GENERAL)), "改为新的海边场景", ""))))
                .isInstanceOf(ImagePromptException.class);
        verifyNoInteractions(provider, analytics);
    }

    @Test
    void trialKeepsGuestAssetScopeAndNeverCreatesFormalHistory() {
        String guest = "a".repeat(64); UUID assetId = UUID.randomUUID();
        var asset = new Asset(assetId, "fixture.png", "image/png", 1, 1, 1, "hash", Instant.now().plusSeconds(3600), "/trial/asset", List.of());
        when(assets.requireGuest(guest, assetId)).thenReturn(asset);
        when(assets.bytesGuest(guest, assetId)).thenReturn(new byte[]{1});
        when(provider.invoke(any())).thenReturn(result("一张测试图片的提示词。", List.of()));
        var doc = service.extractTrial(guest, new Generate(UUID.randomUUID(), task(Mode.REFERENCE_RECONSTRUCTION,
                List.of(new Reference(assetId, ImageRole.GENERAL)), "", "")));
        assertThat(doc.historySaved()).isFalse(); assertThat(doc.persistentHistory()).isFalse(); assertThat(doc.recordId()).isNull();
        verifyNoInteractions(current, analytics);
        verify(assets, never()).require(any(ActorIdentity.class), any());
        assertThat(history.list(actor, 1, 10, null).total()).isZero();
        assertThatThrownBy(() -> service.extractTrial(guest, new Generate(UUID.randomUUID(), task(Mode.CUSTOM_CREATION, List.of(), "新图", ""))))
                .isInstanceOf(ImagePromptException.class);
        verify(provider, times(1)).invoke(any());
    }

    @Test
    void zeroQuestionPlanNeedsNoImagesAndPreservesExactTextDuringGeneration() {
        when(provider.invoke(any())).thenAnswer(inv -> {
            ImagePromptProvider.Call call = inv.getArgument(0);
            return result(call.stage() == Stage.PLAN ? "" : "Create a new botanical poster.", List.of());
        });
        var plan = service.plan(new Generate(UUID.randomUUID(), task(Mode.CUSTOM_CREATION, List.of(), "设计植物展海报，细节委托设计", "春日·2026")));
        assertThat(plan.questions()).isEmpty();
        var document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()));
        assertThat(document.optimizedPrompt()).contains("春日·2026");
        assertThat(document.sources()).isEmpty();
        assertThat(document.mode()).isEqualTo(Mode.CUSTOM_CREATION);
        verifyNoInteractions(assets);
    }

    @Test
    void answersAreBoundToOriginalQuestionsAndBothSelectionAndCustomTextSurvive() {
        var question = new Question("q1", QuestionType.SINGLE_CHOICE, "希望什么风格？",
                List.of(new Option("a", "插画"), new Option("b", "摄影")), true);
        when(provider.invoke(any())).thenReturn(result("", List.of(question)), result("创作新的植物插画海报。", List.of()));
        var plan = service.plan(new Generate(UUID.randomUUID(), task(Mode.CUSTOM_CREATION, List.of(), "植物海报", "")));
        assertThatThrownBy(() -> service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of(new Reply("q1", List.of("invented"), "")))))
                .isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> service.confirm(plan.id(), new Confirm(UUID.randomUUID(), "stale", List.of())))
                .isInstanceOf(ImagePromptException.class);
        var document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of(new Reply("q1", List.of("a"), "保留纸张纹理"))));
        assertThat(document.questions()).containsExactly(question);
        assertThat(document.answers()).containsExactly(new Answer("q1", List.of("a"), "保留纸张纹理"));
        verify(provider, times(2)).invoke(any());
    }

    @Test
    void idempotentReplayReturnsCurrentEditedBodyAndNeverResurrectsDeletedHistory() {
        var generated = generate();
        var document = generated.document();
        history.edit(actor, document.recordId(), "用户修订后的完整正文", 0);
        assertThat(service.operation(document.operationId()).document().optimizedPrompt()).isEqualTo("用户修订后的完整正文");
        assertThat(service.confirm(generated.plan().id(), generated.confirm()).editRevision()).isEqualTo(1);
        verify(provider, times(2)).invoke(any());
        history.delete(actor, document.recordId());
        assertThat(history.list(actor, 1, 10, null).records()).isEmpty();
        assertThatThrownBy(() -> service.retrySave(document.operationId())).isInstanceOf(ImagePromptException.class);
        assertThatThrownBy(() -> history.edit(actor, document.recordId(), "正文", 1)).isInstanceOf(ImagePromptException.class);
    }

    @Test
    void fullOwnerScopeAndOptimisticRevisionProtectHistoryAndPlans() {
        var generated = generate();
        var otherUser = new ActorIdentity(UUID.randomUUID(), actor.tenantId(), actor.workspaceId(), "other@example.test", "other");
        var otherWorkspace = new ActorIdentity(actor.userId(), actor.tenantId(), UUID.randomUUID(), actor.email(), actor.displayName());
        var otherTenant = new ActorIdentity(actor.userId(), UUID.randomUUID(), actor.workspaceId(), actor.email(), actor.displayName());
        for (var other : List.of(otherUser, otherWorkspace, otherTenant)) {
            assertThatThrownBy(() -> history.find(other, generated.document().recordId())).isInstanceOf(ImagePromptException.class);
            assertThat(history.list(other, 1, 10, null).records()).isEmpty();
            when(current.require()).thenReturn(other);
            assertThatThrownBy(() -> service.confirm(generated.plan().id(), generated.confirm())).isInstanceOf(ImagePromptException.class);
        }
        when(current.require()).thenReturn(actor);
        history.edit(actor, generated.document().recordId(), "第一版编辑", 0);
        assertThatThrownBy(() -> history.edit(actor, generated.document().recordId(), "迟到编辑", 0)).isInstanceOf(ImagePromptException.class);
        verify(provider, times(2)).invoke(any());
    }

    @Test
    void failedHistorySaveCanBeRetriedWithoutAnotherModelCall() {
        history = spy(history);
        var beans = new StaticListableBeanFactory();
        service = new ImagePromptService(current, assets, models, provider,
                new ImagePromptStateStore(beans.getBeanProvider(StringRedisTemplate.class), json, "MEMORY"), history, json, analytics);
        doThrow(new DataAccessResourceFailureException("fixture offline")).doCallRealMethod().when(history).save(any(), any());
        var generated = generate();
        assertThat(generated.document().historySaved()).isFalse();
        assertThat(service.retrySave(generated.document().operationId()).historySaved()).isTrue();
        verify(provider, times(2)).invoke(any());
        verify(analytics, times(2)).recordToolOperation(eq(IMAGE_PROMPT_SUBMITTED), any());
        verify(analytics).recordToolOperation(IMAGE_PROMPT_GENERATED, generated.document().operationId());
    }

    @Test
    void planQuestionsFinalResultReplayAndRewriteHaveDistinctAccountFacts() {
        var generated = generate();
        verify(analytics).recordToolOperation(IMAGE_PROMPT_PLAN_CREATED, generated.plan().id());
        verify(analytics).recordToolOperation(IMAGE_PROMPT_GENERATED, generated.document().operationId());
        service.confirm(generated.plan().id(), generated.confirm());
        service.operation(generated.document().operationId());
        service.retrySave(generated.document().operationId());
        verify(analytics, times(2)).recordToolOperation(eq(IMAGE_PROMPT_SUBMITTED), any());
        verify(analytics, times(1)).recordToolOperation(eq(IMAGE_PROMPT_GENERATED), any());
        UUID rewrite = UUID.randomUUID();
        service.rewrite(generated.document().recordId(), new Rewrite(rewrite, 0, "更简洁"));
        verify(analytics).recordToolOperation(IMAGE_PROMPT_SUBMITTED, rewrite);
        verify(analytics).recordToolOperation(IMAGE_PROMPT_GENERATED, rewrite);
    }

    @Test
    void failedModelCallKeepsOneSubmissionWithoutGeneratedFact() {
        UUID id = UUID.randomUUID();
        var request = new Generate(id, task(Mode.CUSTOM_CREATION, List.of(), "植物海报", ""));
        when(provider.invoke(any())).thenThrow(new IllegalStateException("synthetic failure"));
        assertThatThrownBy(() -> service.plan(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.plan(request)).isInstanceOf(ImagePromptException.class);
        verify(analytics).recordToolOperation(IMAGE_PROMPT_SUBMITTED, id);
        verify(analytics, never()).recordToolOperation(eq(IMAGE_PROMPT_PLAN_CREATED), any());
        verify(analytics, never()).recordToolOperation(eq(IMAGE_PROMPT_GENERATED), any());
        verify(provider).invoke(any());
    }

    @Test
    void invalidFinalPromptDoesNotBecomeGeneratedFact() {
        when(provider.invoke(any())).thenReturn(result("", List.of()));
        var plan = service.plan(new Generate(UUID.randomUUID(), task(Mode.CUSTOM_CREATION, List.of(), "植物海报", "")));
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.confirm(plan.id(), new Confirm(id, plan.fingerprint(), List.of())))
                .isInstanceOf(ImagePromptException.class);
        verify(analytics).recordToolOperation(IMAGE_PROMPT_SUBMITTED, id);
        verify(analytics, never()).recordToolOperation(eq(IMAGE_PROMPT_GENERATED), any());
    }

    @Test
    void referenceExtractionCountsOnceAndExpiredAssetDoesNotCountAsSubmission() {
        UUID assetId = UUID.randomUUID();
        var asset = new Asset(assetId, "fixture.png", "image/png", 1, 1, 1, "hash", Instant.now().plusSeconds(3600), "/fixture", List.of());
        when(assets.require(actor, assetId)).thenReturn(asset);
        when(assets.bytes(actor, assetId)).thenReturn(new byte[]{1});
        when(provider.invoke(any())).thenReturn(result("测试图像提示词", List.of()));
        UUID id = UUID.randomUUID();
        var request = new Generate(id, task(Mode.REFERENCE_RECONSTRUCTION, List.of(new Reference(assetId, ImageRole.GENERAL)), "", ""));
        service.extract(request); service.extract(request);
        verify(analytics).recordToolOperation(IMAGE_PROMPT_SUBMITTED, id);
        verify(analytics).recordToolOperation(IMAGE_PROMPT_GENERATED, id);
        clearInvocations(analytics, provider);
        when(assets.require(actor, assetId)).thenThrow(ImagePromptException.expired());
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), request.input()))).isInstanceOf(ImagePromptException.class);
        verifyNoInteractions(analytics, provider);
    }

    private Generated generate() {
        when(provider.invoke(any())).thenReturn(result("", List.of()), result("创作一幅植物海报，不复刻原素材。", List.of()));
        var plan = service.plan(new Generate(UUID.randomUUID(), task(Mode.CUSTOM_CREATION, List.of(), "植物海报", "")));
        var confirm = new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of());
        return new Generated(plan, confirm, service.confirm(plan.id(), confirm));
    }
    private Task task(Mode mode, List<Reference> images, String intent, String exact) {
        return new Task(mode, "fixture", images, intent, exact, "", Language.ZH, "通用文生图");
    }
    private ProviderResult result(String prompt, List<Question> questions) {
        return new ProviderResult(new ProviderDraft("植物海报", prompt, List.of(), questions, List.of()), "fixture", "测试模型", false);
    }
    private record Generated(Plan plan, Confirm confirm, Document document) { }
}
