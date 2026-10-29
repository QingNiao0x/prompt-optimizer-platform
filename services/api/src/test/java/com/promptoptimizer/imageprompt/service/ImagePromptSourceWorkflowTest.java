package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.context.service.impl.*;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import com.promptoptimizer.imageprompt.mapper.ImagePromptMapper;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import java.util.*;
import static com.promptoptimizer.analytics.domain.AnalyticsEventType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通用资料的直接/Plan/混合视觉/幂等与历史闭环；使用真实本地解析器及受控模型端口。 */
class ImagePromptSourceWorkflowTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    private ImagePromptSourceService sources;
    private ImagePromptService service;
    private ImagePromptHistoryService history;
    private ImagePromptProvider provider;
    private ImagePromptMediaService media;
    private ImagePromptAssetService assets;
    private AnalyticsEventService analytics;
    private final List<Call> calls = new ArrayList<>();

    @BeforeEach void setup() {
        var beans = new StaticListableBeanFactory();
        var states = new ImagePromptStateStore(beans.getBeanProvider(StringRedisTemplate.class), json, "MEMORY");
        sources = new ImagePromptSourceService(states, new StreamingDocumentExtractor(new BinaryContentExtractor()), json);
        history = new ImagePromptHistoryService(beans.getBeanProvider(ImagePromptMapper.class), json, false);
        var current = mock(CurrentActor.class); when(current.require()).thenReturn(actor);
        var models = mock(ImagePromptModelService.class);
        when(models.require(eq("fixture"), anyInt())).thenReturn(new Model("fixture", "fixture", true, 2, "fixture", false));
        assets = mock(ImagePromptAssetService.class); media = mock(ImagePromptMediaService.class);
        analytics = mock(AnalyticsEventService.class); provider = mock(ImagePromptProvider.class);
        service = new ImagePromptService(current, assets, models, provider, states, history, json, analytics, media, sources);
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            var analysis = call.stage() == Stage.ANALYZE
                    ? List.of(new Observation(call.images().getFirst().source().id(), "布局", "源图包含两栏", Visibility.OBSERVED)) : List.<Observation>of();
            return new ProviderResult(new ProviderDraft("资料任务", call.stage() == Stage.PLAN || call.stage() == Stage.ANALYZE ? "" : "将所选Vue页面迁移为React，保留接口与权限行为。",
                    analysis, List.of(), List.of()), "fixture", "fixture", false);
        });
    }

    @Test void directCodeChangesDoNotRequirePlanAndUseOnlyReviewedText() throws Exception {
        Task input = authorized(Mode.REFERENCE_RECONSTRUCTION, List.of(), List.of());
        UUID operation = UUID.randomUUID(); var request = new Generate(operation, input);
        var result = service.extract(request);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.stage()).isEqualTo(Stage.GENERATE);
            assertThat(call.images()).isEmpty();
            assertThat(call.sourceSources()).singleElement().satisfies(source -> assertThat(source.units()).hasSize(1));
        });
        assertThat(result.optimizedPrompt()).contains("React", "必须保留", "资料交接清单", "src/Order.vue");
        assertThat(result.sourceSources()).hasSize(1);
        assertThat(result.historySaved()).isTrue();
        assertThat(service.extract(request).recordId()).isEqualTo(result.recordId());
        assertThat(calls).hasSize(1);
        var restored = json.readValue(json.writeValueAsBytes(result), Document.class);
        assertThat(restored.sourceSources()).isEqualTo(result.sourceSources());
        verify(analytics).recordToolOperation(IMAGE_PROMPT_DIRECT_SUBMITTED, operation);
    }

    @Test void planConfirmationUsesBoundSnapshotsAndTracksCompletion() {
        var input = authorized(Mode.CUSTOM_CREATION, List.of(), List.of());
        var plan = service.plan(new Generate(UUID.randomUUID(), input));
        assertThat(plan.sourceSources()).hasSize(1);
        var request = new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of());
        var result = service.confirm(plan.id(), request);
        assertThat(calls).extracting(Call::stage).containsExactly(Stage.PLAN, Stage.GENERATE);
        assertThat(calls.getLast().sourceSources()).isEqualTo(plan.sourceSources());
        assertThat(result.sourceSources()).isEqualTo(plan.sourceSources());
        verify(analytics).recordToolOperation(IMAGE_PROMPT_PLAN_COMPLETED, plan.id());
        service.confirm(plan.id(), request);
        assertThat(calls).hasSize(2);
    }

    @Test void mixedDirectInputAnalyzesPixelsSeparatelyAndDoesNotSendTextIntoVisualAnalysis() {
        UUID imageId = UUID.randomUUID(), unitId = UUID.randomUUID();
        var references = List.of(new MaterialRef(imageId, Track.REPLACEMENT, ImageRole.LAYOUT, List.of(new UnitSelection(unitId, List.of()))));
        when(media.resolve(actor, references)).thenReturn(new Resolved(List.of(new ImageData(new ImageSource(unitId, "layout.png", "hash", ImageRole.LAYOUT,
                1, 1, Instant.now().plusSeconds(3600)), "image/png", new byte[]{1})), List.of(new MaterialSource(imageId, "layout.png", Kind.IMAGE,
                Track.REPLACEMENT, ImageRole.LAYOUT, List.of(new SourceUnit(unitId, null, null, "", List.of())), List.of())), List.of()));
        var result = service.extract(new Generate(UUID.randomUUID(), authorized(Mode.REFERENCE_RECONSTRUCTION, references, List.of())));
        assertThat(calls).extracting(Call::stage).containsExactly(Stage.ANALYZE, Stage.GENERATE);
        assertThat(calls.getFirst().sourceSources()).isEmpty();
        assertThat(calls.getLast().images()).isEmpty();
        assertThat(calls.getLast().sourceSources()).hasSize(1);
        assertThat(result.materialSources()).hasSize(1);
        assertThat(result.analysis()).hasSize(1);
    }

    @Test void missingConsentOrChangedModelFailsBeforeAnyModelCall() {
        var authorized = authorized(Mode.REFERENCE_RECONSTRUCTION, List.of(), List.of());
        var source = authorized.sourceTask();
        var missing = new ImagePromptSourceModels.Task(source.taskType(), source.sources(), null);
        var input = new Task(authorized.mode(), authorized.modelId(), List.of(), authorized.creationIntent(), authorized.exactText(), "", Language.ZH,
                "通用", OutputType.IMAGE, List.of(), List.of(), missing);
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), input))).isInstanceOf(ImagePromptException.class);
        var changed = new Task(authorized.mode(), "other", List.of(), authorized.creationIntent(), authorized.exactText(), "", Language.ZH,
                "通用", OutputType.IMAGE, List.of(), List.of(), source);
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), changed))).isInstanceOf(ImagePromptException.class);
        verifyNoInteractions(provider);
    }

    @Test void revokedSourceStopsPlanConfirmationWithoutGenerating() {
        var input = authorized(Mode.CUSTOM_CREATION, List.of(), List.of());
        var plan = service.plan(new Generate(UUID.randomUUID(), input));
        sources.revoke(actor, input.sourceTask().sources().getFirst().assetId());
        assertThatThrownBy(() -> service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()))).isInstanceOf(ImagePromptException.class);
        assertThat(calls).hasSize(1);
    }

    @Test void editingAndRewritePreserveSourceSnapshotsAfterRevocationAndDeletionCannotReviveHistory() {
        var input = authorized(Mode.REFERENCE_RECONSTRUCTION, List.of(), List.of());
        var original = service.extract(new Generate(UUID.randomUUID(), input));
        var edited = history.edit(actor, original.recordId(), "人工编辑的迁移任务", 0);
        assertThat(edited.sourceSources()).isEqualTo(original.sourceSources());
        sources.revoke(actor, input.sourceTask().sources().getFirst().assetId());
        var rewriteId = UUID.randomUUID();
        var rewritten = service.rewrite(original.recordId(), new Rewrite(rewriteId, 1, "补充测试要求"));
        assertThat(calls.getLast().stage()).isEqualTo(Stage.REWRITE);
        assertThat(calls.getLast().previousPrompt()).isEqualTo("人工编辑的迁移任务");
        assertThat(rewritten.sourceSources()).isEqualTo(original.sourceSources());
        verify(analytics).recordToolOperation(IMAGE_PROMPT_REOPTIMIZED, rewriteId);
        history.delete(actor, original.recordId());
        assertThatThrownBy(() -> service.retrySave(original.operationId())).isInstanceOf(ImagePromptException.class);
    }

    private Task authorized(Mode mode, List<MaterialRef> visual, List<ReplacementRelation> relations) {
        var asset = sources.text(actor, new ImagePromptSourceModels.TextUpload("订单模块", List.of(new ImagePromptSourceModels.Entry("src/Order.vue", "<template><button>保存</button></template>"))));
        var ref = new ImagePromptSourceModels.Reference(asset.id(), List.of(asset.units().getFirst().id()), Track.REFERENCE, ImagePromptSourceModels.Role.BASELINE);
        var source = new ImagePromptSourceModels.Task(ImagePromptSourceModels.TaskType.CODE, List.of(ref), null);
        var input = new Task(mode, "fixture", List.of(), "将Vue订单页面迁移为React，保留接口与交互", "必须保留", "", Language.ZH, "通用", OutputType.IMAGE, visual, relations, source);
        var consent = sources.consent(actor, input.input());
        return new Task(mode, "fixture", List.of(), input.creationIntent(), input.exactText(), "", Language.ZH, "通用", OutputType.IMAGE, visual, relations,
                new ImagePromptSourceModels.Task(source.taskType(), source.sources(), consent.id()));
    }
}
