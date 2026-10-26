package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 多媒体识别分批、选择范围、关系保真与旧历史兼容；全部模型响应为受控夹具。 */
class ImagePromptMultimodalServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CurrentActor current = mock(CurrentActor.class);
    private final ImagePromptAssetService assets = mock(ImagePromptAssetService.class);
    private final ImagePromptMediaService media = mock(ImagePromptMediaService.class);
    private final ImagePromptModelService models = mock(ImagePromptModelService.class);
    private final ImagePromptProvider provider = mock(ImagePromptProvider.class);
    private final AnalyticsEventService analytics = mock(AnalyticsEventService.class);
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture@example.test", "fixture");
    private final UUID reference = UUID.randomUUID(), replacement = UUID.randomUUID(), video = UUID.randomUUID();
    private final UUID slide = UUID.randomUUID(), frame = UUID.randomUUID(), product = UUID.randomUUID();
    private ImagePromptService service;
    private ImagePromptHistoryService history;

    @BeforeEach void setup() {
        var beans = new StaticListableBeanFactory();
        history = new ImagePromptHistoryService(beans.getBeanProvider(com.promptoptimizer.imageprompt.mapper.ImagePromptMapper.class), json, false);
        service = new ImagePromptService(current, assets, models, provider,
                new ImagePromptStateStore(beans.getBeanProvider(StringRedisTemplate.class), json, "MEMORY"), history, json, analytics, media);
        when(current.require()).thenReturn(actor);
        when(models.require(eq("fixture"), anyInt())).thenReturn(new Model("fixture", "fixture", true, 1, "fixture", false));
    }

    @Test void batchesByActualModelLimitThenReusesAnalysisForPlanAndGeneration() {
        var selected = materials();
        var sources = sources();
        when(media.resolve(actor, selected)).thenReturn(new Resolved(List.of(image(slide), image(frame), image(product)), sources,
                List.of("视频仅覆盖选定帧，音轨未分析。")));
        var calls = new ArrayList<Call>();
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            if (call.stage() == Stage.ANALYZE) return new ProviderResult(new ProviderDraft("受控素材", "",
                    List.of(new Observation(call.images().getFirst().source().id(), "主体", "合成产品", Visibility.OBSERVED)), List.of(), List.of()),
                    "fixture", "fixture", false, 2);
            return new ProviderResult(new ProviderDraft("视频创作", call.stage() == Stage.PLAN ? "" : "为新产品耳机制作视频，保留参考配色，并保留新产品标识。",
                    List.of(), List.of(), List.of()), "fixture", "fixture", false);
        });
        var relation = new ReplacementRelation("swap-1", RelationOperation.REPLACE, reference, slide, "页面中央旧产品", replacement,
                product, "耳机", "保留新产品标识", List.of("主体", "构图"), "第一个镜头");
        var preserve = new ReplacementRelation("keep-palette", RelationOperation.PRESERVE, reference, slide, "参考配色", null, null,
                "", "保留参考配色，不迁移原主体", List.of("配色"), "全部镜头");
        Plan plan = service.plan(new Generate(UUID.randomUUID(), task(selected, List.of(preserve, relation))));
        assertThat(plan.materialSources()).isEqualTo(sources);
        assertThat(plan.analysis()).hasSize(3);
        Document document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()));
        assertThat(calls).extracting(Call::stage).containsExactly(Stage.ANALYZE, Stage.ANALYZE, Stage.ANALYZE, Stage.PLAN, Stage.GENERATE);
        assertThat(calls.subList(0, 3)).allSatisfy(call -> assertThat(call.images()).hasSize(1));
        assertThat(calls.subList(3, 5)).allSatisfy(call -> { assertThat(call.images()).isEmpty(); assertThat(call.analysis()).hasSize(3); });
        assertThat(document.optimizedPrompt()).contains("R1", "T1", "参考.pptx", "新产品.png", "第2页", "1.500s", "耳机", "保留新产品标识", "保留参考配色");
        assertThat(calls.getLast().input().relations()).containsExactly(preserve, relation);
        assertThat(document.optimizedPrompt()).doesNotContain(reference.toString(), slide.toString(), replacement.toString());
        assertThat(document.warnings()).contains("视频仅覆盖选定帧，音轨未分析。");
        assertThat(history.find(actor, document.recordId()).materialSources()).isEqualTo(sources);
        verify(media, times(1)).resolve(actor, selected);
        verify(media, times(7)).require(actor, reference);
        verify(media, times(7)).require(actor, replacement);
        verifyNoInteractions(assets);
        Document rewritten = service.rewrite(document.recordId(), new Rewrite(UUID.randomUUID(), 0, "让表达更简洁"));
        assertThat(calls.getLast().stage()).isEqualTo(Stage.REWRITE);
        assertThat(calls.getLast().previousPrompt()).doesNotContain("素材对照：");
        assertThat(rewritten.optimizedPrompt().split("素材对照：", -1)).hasSize(2);
        assertThat(rewritten.materialSources()).isEqualTo(sources);
        assertThat(rewritten.optimizedPrompt()).contains("仅使用所选页面／视频帧，并保留本次遮挡");
    }

    @Test void relationCannotReferToUnselectedUnitOrForeignAsset() {
        var unknownUnit = new ReplacementRelation("swap", RelationOperation.REPLACE, reference, UUID.randomUUID(), "主体", replacement,
                product, "新主体", "", List.of(), "");
        assertThatThrownBy(() -> service.plan(new Generate(UUID.randomUUID(), task(materials(), List.of(unknownUnit)))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("未选择");
        var unknownAsset = new ReplacementRelation("swap", RelationOperation.PRESERVE, UUID.randomUUID(), null, "主体", null,
                null, "", "保留配色", List.of(), "");
        assertThatThrownBy(() -> service.plan(new Generate(UUID.randomUUID(), task(materials(), List.of(unknownAsset)))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("未加入");
        verifyNoInteractions(provider, media);
    }

    @Test void legacyImagesAndMultimodalMaterialsShareTheSameSixAssetLimit() {
        var legacy = java.util.stream.IntStream.range(0, 3).mapToObj(index -> new Reference(UUID.randomUUID(), ImageRole.GENERAL)).toList();
        var selected = java.util.stream.IntStream.range(0, 4).mapToObj(index -> new MaterialRef(UUID.randomUUID(), Track.REFERENCE,
                ImageRole.GENERAL, List.of(new UnitSelection(UUID.randomUUID(), List.of())))).toList();
        var combined = new Task(Mode.CUSTOM_CREATION, "fixture", legacy, "使用参考素材进行内容创作", "", "", Language.ZH,
                "通用", OutputType.IMAGE, selected, List.of());
        assertThatThrownBy(() -> service.plan(new Generate(UUID.randomUUID(), combined)))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("6份");
        verifyNoInteractions(assets, media, provider, analytics, models);
    }

    @Test void reconstructionRejectsMultimediaAndKnownSensitiveMaterialNeverReachesModel() {
        var referenceTask = new Task(Mode.REFERENCE_RECONSTRUCTION, "fixture", List.of(new Reference(UUID.randomUUID(), ImageRole.GENERAL)),
                "", "", "", Language.ZH, "通用", OutputType.IMAGE, materials(), List.of());
        assertThatThrownBy(() -> service.extract(new Generate(UUID.randomUUID(), referenceTask))).isInstanceOf(ImagePromptException.class);
        var source = new MaterialSource(reference, "参考.pptx", Kind.PPTX, Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new SourceUnit(slide, 2, null, "api_key=fixtureSecretCredential123", List.of())), List.of());
        when(media.resolve(actor, materials())).thenReturn(new Resolved(List.of(image(slide)), List.of(source), List.of()));
        assertThatThrownBy(() -> service.plan(new Generate(UUID.randomUUID(), task(materials(), List.of()))))
                .isInstanceOf(ImagePromptException.class).hasMessageContaining("敏感");
        verifyNoInteractions(provider);
    }

    @Test void oldInputAndHistoryPayloadsDefaultToImageAndEmptyMaterials() throws Exception {
        Input original = new Input(Mode.CUSTOM_CREATION, "fixture", List.of(), "合成海报", "", "", Language.ZH, "通用");
        ObjectNode input = json.valueToTree(original); input.remove(List.of("outputType", "materials", "relations"));
        Input read = json.treeToValue(input, Input.class);
        assertThat(read.outputType()).isEqualTo(OutputType.IMAGE); assertThat(read.materials()).isEmpty(); assertThat(read.relations()).isEmpty();
        var document = new Document(UUID.randomUUID(), null, Mode.CUSTOM_CREATION, "合成海报提示词", List.of(), List.of(), original,
                List.of(), List.of(), List.of(), "fixture", "fixture", false, 0, false, false, Instant.now());
        ObjectNode payload = json.valueToTree(document); payload.remove(List.of("materialSources", "evidenceWarnings")); payload.set("input", input);
        Document restored = json.treeToValue(payload, Document.class);
        assertThat(restored.materialSources()).isEmpty(); assertThat(restored.input().outputType()).isEqualTo(OutputType.IMAGE);
        assertThat(restored.edited("修订正文", 1).input()).isEqualTo(read);
    }

    @Test void revokedMaterialStopsBeforeSendingTheNextBatch() {
        when(media.resolve(actor, materials())).thenReturn(new Resolved(List.of(image(slide), image(frame), image(product)), sources(), List.of()));
        var analyses = new java.util.concurrent.atomic.AtomicInteger();
        when(media.require(actor, reference)).thenAnswer(invocation -> {
            if (analyses.get() > 0) throw ImagePromptException.missing();
            return null;
        });
        when(provider.invoke(any())).thenAnswer(invocation -> {
            analyses.incrementAndGet();
            return new ProviderResult(new ProviderDraft("观察", "", List.of(), List.of(), List.of()), "fixture", "fixture", false);
        });
        assertThatThrownBy(() -> service.plan(new Generate(UUID.randomUUID(), task(materials(), List.of()))))
                .isInstanceOf(ImagePromptException.class);
        verify(provider, times(1)).invoke(any());
        assertThat(history.list(actor, 1, 10, null).total()).isZero();
    }

    @Test void twelvePagesKeepTheirObservationsAndShareTheBoundedRepairBudget() {
        var units = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(page -> new SourceUnit(UUID.randomUUID(), page, null, "合成原文", List.of())).toList();
        var refs = List.of(new MaterialRef(reference, Track.REFERENCE, ImageRole.LAYOUT,
                units.stream().map(unit -> new UnitSelection(unit.id(), List.of())).toList()));
        var source = new MaterialSource(reference, "参考.pptx", Kind.PPTX, Track.REFERENCE, ImageRole.LAYOUT, units, List.of());
        when(media.resolve(actor, refs)).thenReturn(new Resolved(units.stream().map(unit -> image(unit.id())).toList(), List.of(source), List.of()));
        var calls = new ArrayList<Call>();
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            List<Observation> observations = call.stage() == Stage.ANALYZE
                    ? java.util.stream.IntStream.range(0, 6).mapToObj(index -> new Observation(call.images().getFirst().source().id(),
                    "维度" + index, "合成页面的独立观察" + index, Visibility.OBSERVED)).toList()
                    : call.stage() == Stage.PLAN ? List.of(new Observation(units.getFirst().id(), "版式解释", "模型提出的布局建议", Visibility.INTERPRETATION)) : List.of();
            return new ProviderResult(new ProviderDraft("页面参考", call.stage() == Stage.GENERATE ? "根据选定页面创作新视频。" : "",
                    observations, List.of(), List.of()), "fixture", "fixture", false, 2);
        });
        var plan = service.plan(new Generate(UUID.randomUUID(), task(refs, List.of())));
        assertThat(plan.analysis()).hasSize(72);
        var document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()));
        assertThat(document.analysis()).hasSize(72);
        assertThat(calls).hasSize(14).allSatisfy(call -> assertThat(call.maxAttempts()).isEqualTo(2));
        assertThat(calls.stream().mapToInt(Call::maxAttempts).sum()).isEqualTo(28);
    }

    @Test void pptTargetTitleCannotBecomeAnObservedSourceFactDuringPlanning() throws Exception {
        var fixture = fixture("M02-ppt-page-data");
        Task task = json.treeToValue(fixture.path("input"), Task.class);
        var sources = fixtureList(fixture, "materialSources", MaterialSource.class);
        stubMaterials(task, sources);
        var original = fixtureList(fixture, "originalAnalysis", Observation.class);
        var sourceFacts = original.subList(0, original.size() - 1);
        var creativeWish = original.getLast();
        var calls = new ArrayList<Call>();
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            return new ProviderResult(switch (call.stage()) {
                case ANALYZE -> new ProviderDraft("源页面", "", sourceFacts, List.of(), List.of("源表格边缘需核对"));
                case PLAN -> new ProviderDraft("将标题改为季度指标·2046", "", List.of(creativeWish), List.of(), List.of("原标题与新标题不同，请确认"));
                default -> new ProviderDraft("PPT制作要求", "将一页PPT标题逐字改为季度指标·2046，并保留原表格数值。", List.of(), List.of(), List.of());
            }, "fixture", "fixture", false);
        });
        var plan = service.plan(new Generate(UUID.randomUUID(), task));
        assertThat(plan.analysis()).containsExactlyElementsOf(sourceFacts).doesNotContain(creativeWish);
        var document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()));
        assertThat(document.analysis()).containsExactlyElementsOf(sourceFacts);
        assertThat(document.optimizedPrompt()).contains("季度指标·2046");
        assertThat(document.warnings()).contains("源表格边缘需核对").doesNotContain("原标题与新标题不同，请确认");
        assertThat(calls.getLast().input().relations()).isEqualTo(task.input().relations());
        assertThat(calls.getLast().input().exactText()).isEqualTo("季度指标·2046");
        replayEvidence("M02-ppt-page-data", Map.of("candidate", document, "ignoredPlanningObservation", creativeWish,
                "sourceFactCount", sourceFacts.size(), "originalPlanSha256", fixture.path("originalPlanSha256").asText()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"swap", "preserve", "custom"})
    void confirmedConflictNeverReplaysSupersededOriginalRelations(String choice) throws Exception {
        var fixture = fixture("C01-relation-conflict");
        Task task = json.treeToValue(fixture.path("input"), Task.class);
        var questions = fixtureList(fixture, "questions", Question.class);
        var sources = fixtureList(fixture, "materialSources", MaterialSource.class);
        stubMaterials(task, sources);
        var actual = fixtureList(fixture, "answers", Answer.class).getFirst();
        Answer answer = choice.equals("preserve") ? new Answer(actual.questionId(), List.of("o1"),
                "采用保留方案：保留原杯形和橙色作为唯一主体，不采用耳机替换；其余已给版式、比例和标题继续执行。")
                : new Answer(actual.questionId(), choice.equals("custom") ? List.of() : actual.optionIds(), actual.customText());
        String modelBody = choice.equals("preserve")
                ? "创作3:2横向海报，奶油底，标题逐字为SUMMER SOUND，中央唯一主体保持原杯形和橙色，不采用耳机替换。其余字体与间距按原委托设计。"
                : fixture.path("modelBody").asText();
        var calls = new ArrayList<Call>();
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            return new ProviderResult(switch (call.stage()) {
                case ANALYZE -> new ProviderDraft("合成源素材", "", List.of(new Observation(call.images().getFirst().source().id(),
                        "主体", "可见的合成物体", Visibility.OBSERVED)), List.of(), List.of("源图边缘的小字需核对"));
                case PLAN -> new ProviderDraft("需要确认中央主体取舍", "", List.of(), questions, List.of("两条关系互斥，未确认不能生成"));
                default -> new ProviderDraft("已落实实际取舍", modelBody, List.of(), List.of(), List.of("最终设计按本次实际确认执行"));
            }, "fixture", "fixture", false);
        });
        var plan = service.plan(new Generate(UUID.randomUUID(), task));
        Document document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(),
                List.of(new Reply(answer.questionId(), answer.optionIds(), answer.customText()))));
        String inactiveInstruction = task.relations().get(choice.equals("preserve") ? 1 : 0).instruction();
        assertThat(document.optimizedPrompt()).startsWith(modelBody).contains("已确认补充要求：", answer.customText(), "SUMMER SOUND")
                .contains("列出条目不表示需要全部用于成品", "原新内容来源").doesNotContain(inactiveInstruction, "素材上传说明与已明确的关系：");
        for (String selected : answer.optionIds()) assertThat(document.optimizedPrompt()).contains(questions.getFirst().options().stream()
                .filter(option -> option.id().equals(selected)).findFirst().orElseThrow().label());
        assertThat(document.input().relations()).isEqualTo(task.input().relations());
        assertThat(document.questions()).isEqualTo(questions); assertThat(document.answers()).containsExactly(answer);
        assertThat(document.warnings()).contains("源图边缘的小字需核对", "最终设计按本次实际确认执行").doesNotContain("两条关系互斥，未确认不能生成");
        assertThat(history.find(actor, document.recordId()).optimizedPrompt()).isEqualTo(document.optimizedPrompt());
        var rewritten = service.rewrite(document.recordId(), new Rewrite(UUID.randomUUID(), 0, "精简表达，保留实际确认和其他独立要求"));
        assertThat(calls.getLast().previousPrompt()).isEqualTo(modelBody);
        assertThat(rewritten.optimizedPrompt()).doesNotContain(inactiveInstruction).contains(answer.customText());
        assertThat(rewritten.optimizedPrompt().split("已确认补充要求：", -1)).hasSize(2);
        replayEvidence("C01-relation-conflict-" + choice, Map.of("candidate", document, "rewritten", rewritten,
                "inactiveOriginalInstruction", inactiveInstruction, "originalGenerateSha256", fixture.path("originalGenerateSha256").asText()));
    }

    @Test void rewritingV4HistoryRemovesOnlyItsExactGeneratedTailAndKeepsOriginalRecord() throws Exception {
        var fixture = fixture("C01-relation-conflict");
        Task task = json.treeToValue(fixture.path("input"), Task.class);
        var questions = fixtureList(fixture, "questions", Question.class);
        var answers = fixtureList(fixture, "answers", Answer.class);
        var sources = fixtureList(fixture, "materialSources", MaterialSource.class);
        String original = fixture.path("originalAssembledPrompt").asText();
        var originalWarnings = fixtureList(fixture, "originalWarnings", String.class);
        var sourceUncertainty = new Observation(sources.getFirst().units().getFirst().id(), "文字", "来源边缘小字无法辨认", Visibility.UNCERTAIN);
        var old = history.save(actor, new Document(UUID.randomUUID(), null, Mode.CUSTOM_CREATION, original, List.of(sourceUncertainty), originalWarnings, task.input(),
                List.of(), questions, answers, "fixture", "fixture", false, 0, false, false, Instant.now(), sources));
        var calls = new ArrayList<Call>();
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            return new ProviderResult(new ProviderDraft("已确认创作", fixture.path("modelBody").asText(), List.of(), List.of(), List.of()), "fixture", "fixture", false);
        });
        var rewritten = service.rewrite(old.recordId(), new Rewrite(UUID.randomUUID(), 0, "保留实际确认，整理表达"));
        assertThat(calls).singleElement().satisfies(call -> assertThat(call.previousPrompt()).isEqualTo(fixture.path("modelBody").asText()));
        assertThat(rewritten.optimizedPrompt()).doesNotContain(task.relations().getFirst().instruction()).contains(answers.getFirst().customText());
        assertThat(history.find(actor, old.recordId()).optimizedPrompt()).isEqualTo(original);
        assertThat(history.find(actor, old.recordId()).warnings()).isEqualTo(originalWarnings);
        assertThat(rewritten.warnings()).contains("来源边缘小字无法辨认", sources.getFirst().warnings().getFirst())
                .doesNotContain("两张关系卡对同一中央主体分别要求保留橙色杯子和替换为青绿耳机，二者互斥，未确认前无法生成。");
        replayEvidence("C01-v4-history-rewrite", Map.of("originalUnchanged", history.find(actor, old.recordId()).optimizedPrompt().equals(original),
                "originalWarningsUnchanged", history.find(actor, old.recordId()).warnings().equals(originalWarnings),
                "originalWarningCount", originalWarnings.size(), "rewritten", rewritten, "providerReceivedBody", calls.getFirst().previousPrompt()));
    }

    @Test void legacyImagePlanKeepsItsVisualAnalysisAndCombinedWarnings() {
        UUID legacy = UUID.randomUUID();
        when(assets.require(actor, legacy)).thenReturn(new Asset(legacy, "legacy.png", "image/png", 1, 1, 1, "hash",
                Instant.now().plusSeconds(3600), "/private", List.of("原图已清除元数据")));
        when(assets.bytes(actor, legacy)).thenReturn(new byte[]{1});
        var observation = new Observation(legacy, "文字", "源图角落文字无法辨认", Visibility.UNCERTAIN);
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0);
            if (call.stage() == Stage.PLAN) {
                assertThat(call.images()).hasSize(1);
                return new ProviderResult(new ProviderDraft("旧图参考", "", List.of(observation), List.of(), List.of("创作方向待确认")), "fixture", "fixture", false);
            }
            return new ProviderResult(new ProviderDraft("海报创作", "按确认的构想创作新海报。", List.of(), List.of(), List.of()), "fixture", "fixture", false);
        });
        var task = new Task(Mode.CUSTOM_CREATION, "fixture", List.of(new Reference(legacy, ImageRole.STYLE)), "设计新海报，普通细节委托设计", "", "", Language.ZH, "通用");
        var plan = service.plan(new Generate(UUID.randomUUID(), task));
        assertThat(plan.analysis()).containsExactly(observation);
        var document = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of()));
        assertThat(document.warnings()).contains("原图已清除元数据", "源图角落文字无法辨认", "创作方向待确认");
        assertThat(document.evidenceWarnings()).contains("原图已清除元数据", "源图角落文字无法辨认").doesNotContain("创作方向待确认");
        verifyNoInteractions(media);
    }

    @Test void oldBoundPlanCachesRecoverKnownSourceRisksWithoutTreatingPlanWarningsAsCurrent() throws Exception {
        var input = new Input(Mode.CUSTOM_CREATION, "fixture", List.of(), "内容创作", "", "", Language.ZH, "通用");
        var source = new MaterialSource(reference, "source.png", Kind.IMAGE, Track.REFERENCE, ImageRole.STYLE,
                List.of(new SourceUnit(slide, null, null, "", List.of())), List.of("源素材仅覆盖所选单位"));
        var view = new Plan(UUID.randomUUID(), "fingerprint", "创作", List.of(),
                List.of(new Observation(slide, "文字", "源小字无法辨认", Visibility.UNCERTAIN)), List.of("尚未回答不能生成"), Instant.now().plusSeconds(3600), false, List.of(source));
        ObjectNode old = json.valueToTree(new ImagePromptService.BoundPlan(input, List.of(), view, 2, List.of()));
        old.remove("evidenceWarnings");
        var restored = json.treeToValue(old, ImagePromptService.BoundPlan.class);
        assertThat(restored.evidenceWarnings()).contains("源素材仅覆盖所选单位", "源小字无法辨认").doesNotContain("尚未回答不能生成");
        assertThat(restored.view().warnings()).containsExactly("尚未回答不能生成");
    }

    @Test void warningOnlySourceRiskSurvivesJsonHistoryEditUndoAndRewrite() throws Exception {
        var selected = materials();
        when(media.resolve(actor, selected)).thenReturn(new Resolved(List.of(image(slide), image(frame), image(product)), sources(), List.of()));
        String sourceRisk = "源页角落的小字无法可靠辨认，不能据此编造具体数值。";
        String pending = "请先回答创作偏好再生成。";
        var calls = new ArrayList<Call>();
        var question = new Question("q1", QuestionType.TEXT, "希望采用什么视觉风格？", List.of(), true);
        when(provider.invoke(any())).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0); calls.add(call);
            return new ProviderResult(switch (call.stage()) {
                case ANALYZE -> new ProviderDraft("源素材", "", List.of(new Observation(call.images().getFirst().source().id(), "主体", "合成产品", Visibility.OBSERVED)),
                        List.of(), List.of(sourceRisk));
                case PLAN -> new ProviderDraft("创作偏好待确认", "", List.of(), List.of(question), List.of(pending));
                default -> new ProviderDraft("创作要求", "创作一个合成产品视频，不编造无法辨认的小字。", List.of(), List.of(), List.of("本次风格按确认处理。"));
            }, "fixture", "fixture", false);
        });
        var plan = service.plan(new Generate(UUID.randomUUID(), task(selected, List.of())));
        var generated = service.confirm(plan.id(), new Confirm(UUID.randomUUID(), plan.fingerprint(), List.of(new Reply("q1", List.of(), "风格由下游设计"))));
        assertThat(generated.analysis()).noneMatch(observation -> observation.status() == Visibility.UNCERTAIN);
        assertThat(generated.evidenceWarnings()).containsExactly(sourceRisk);
        assertThat(generated.warnings()).contains(sourceRisk).doesNotContain(pending);
        var serialized = json.readValue(json.writeValueAsString(history.find(actor, generated.recordId())), Document.class);
        assertThat(serialized.evidenceWarnings()).containsExactly(sourceRisk);
        var edited = history.edit(actor, generated.recordId(), generated.optimizedPrompt() + "\n人工补充表达。", 0);
        assertThat(edited.evidenceWarnings()).containsExactly(sourceRisk);
        var undone = history.edit(actor, generated.recordId(), generated.optimizedPrompt(), 1);
        assertThat(undone.evidenceWarnings()).containsExactly(sourceRisk);
        var rewritten = service.rewrite(generated.recordId(), new Rewrite(UUID.randomUUID(), 2, "整理表达并保留来源限制"));
        assertThat(rewritten.evidenceWarnings()).containsExactly(sourceRisk);
        assertThat(rewritten.warnings()).contains(sourceRisk).doesNotContain(pending);
        assertThat(calls.getLast().evidenceWarnings()).containsExactly(sourceRisk);
        replayEvidence("warning-only-risk-persistence", Map.of("candidate", generated, "rewritten", rewritten,
                "jsonRoundTrip", serialized.evidenceWarnings(), "editedRisk", edited.evidenceWarnings(), "undoRisk", undone.evidenceWarnings()));
    }

    @Test void absentOrNullEvidenceUsesKnownSourcesButExplicitEmptyStaysEmpty() throws Exception {
        var source = new MaterialSource(reference, "source.png", Kind.IMAGE, Track.REFERENCE, ImageRole.STYLE,
                List.of(new SourceUnit(slide, null, null, "", List.of())), List.of("本次只选取部分来源"));
        var input = new Input(Mode.CUSTOM_CREATION, "fixture", List.of(), "内容创作", "", "", Language.ZH, "通用");
        var original = new Document(UUID.randomUUID(), null, Mode.CUSTOM_CREATION, "创作提示词", List.of(), List.of("尚未回答不能生成"), input,
                List.of(), List.of(), List.of(), "fixture", "fixture", false, 0, false, false, Instant.now(), List.of(source), List.of());
        assertThat(original.evidenceWarnings()).isEmpty();
        ObjectNode payload = json.valueToTree(original); payload.remove("evidenceWarnings");
        assertThat(json.treeToValue(payload, Document.class).evidenceWarnings()).containsExactly("本次只选取部分来源");
        payload.putNull("evidenceWarnings");
        assertThat(json.treeToValue(payload, Document.class).evidenceWarnings()).containsExactly("本次只选取部分来源");
        payload.putArray("evidenceWarnings");
        assertThat(json.treeToValue(payload, Document.class).evidenceWarnings()).isEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/imageprompt/" + name + ".json")) {
            if (stream == null) throw new IllegalStateException("缺少受控回放夹具");
            return json.readTree(stream);
        }
    }
    private <T> List<T> fixtureList(com.fasterxml.jackson.databind.JsonNode fixture, String field, Class<T> type) {
        return json.convertValue(fixture.path(field), json.getTypeFactory().constructCollectionType(List.class, type));
    }
    private void stubMaterials(Task task, List<MaterialSource> sources) {
        var images = sources.stream().flatMap(source -> source.units().stream()).map(unit -> image(unit.id())).toList();
        var warnings = sources.stream().flatMap(source -> source.warnings().stream()).distinct().toList();
        when(media.resolve(actor, task.materials())).thenReturn(new Resolved(images, sources, warnings));
    }
    /** 只输出受控合成夹具回放结果到构建目录，供评测冻结核对；不包含认证数据或真实模型调用。 */
    private void replayEvidence(String name, Map<String, Object> result) throws Exception {
        var directory = java.nio.file.Path.of("target", "imageprompt-source-boundaries-replay");
        java.nio.file.Files.createDirectories(directory);
        var artifact = new LinkedHashMap<String, Object>(); artifact.put("version", ImagePromptInstructions.VERSION);
        artifact.put("caseId", name); artifact.put("modelCalls", "LOCAL_STUB_ONLY"); artifact.putAll(result);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), artifact);
    }

    private Task task(List<MaterialRef> materials, List<ReplacementRelation> relations) {
        return new Task(Mode.CUSTOM_CREATION, "fixture", List.of(), "为新产品设计视频，参考选定页面和素材，未指定细节委托设计", "", "", Language.ZH,
                "通用视频工具", OutputType.VIDEO, materials, relations);
    }
    private List<MaterialRef> materials() {
        return List.of(new MaterialRef(reference, Track.REFERENCE, ImageRole.LAYOUT,
                        List.of(new UnitSelection(slide, List.of()))),
                new MaterialRef(video, Track.REFERENCE, ImageRole.GENERAL, List.of(new UnitSelection(frame, List.of()))),
                new MaterialRef(replacement, Track.REPLACEMENT, ImageRole.SUBJECT, List.of(new UnitSelection(product, List.of()))));
    }
    private List<MaterialSource> sources() {
        return List.of(new MaterialSource(reference, "参考.pptx", Kind.PPTX, Track.REFERENCE, ImageRole.LAYOUT,
                        List.of(new SourceUnit(slide, 2, null, "合成标题", List.of())), List.of()),
                new MaterialSource(video, "参考视频.mp4", Kind.VIDEO, Track.REFERENCE, ImageRole.GENERAL,
                        List.of(new SourceUnit(frame, null, 1500L, "", List.of())), List.of()),
                new MaterialSource(replacement, "新产品.png", Kind.IMAGE, Track.REPLACEMENT, ImageRole.SUBJECT,
                        List.of(new SourceUnit(product, null, null, "", List.of())), List.of()));
    }
    private ImageData image(UUID unit) {
        return new ImageData(new ImageSource(unit, "合成素材", "fixture-hash", ImageRole.GENERAL, 1, 1, Instant.now().plusSeconds(3600)), "image/png", new byte[]{1});
    }
}
