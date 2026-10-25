package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 润色来源、私有隔离、幂等、编辑和删除闭环；不调用外部模型或创建账号。 */
class TextPolishServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ActorIdentity a = new ActorIdentity(new UUID(0,1), new UUID(0,2), new UUID(0,3), "fixture@local", "fixture");
    private final ModelEntry model = new ModelEntry(new UUID(0,4), "fixture", "mock", "fixture", "测试模型", true, true, 0);
    private final TextPolishFidelity fidelity = new TextPolishFidelity();
    private CurrentActor current; private TextPolishProvider provider; private TextPolishHistoryService history; private TextPolishService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        current = mock(CurrentActor.class); when(current.require()).thenReturn(a);
        provider = mock(TextPolishProvider.class); when(provider.polish(any(), any())).thenAnswer(i -> ((Input)i.getArgument(0)).sourceText());
        var models = mock(TextPolishModelService.class); when(models.require(any())).thenReturn(model);
        history = new TextPolishHistoryService(mock(ObjectProvider.class), json, fidelity, false);
        var states = new TextPolishStateStore(mock(ObjectProvider.class), json, "MEMORY");
        service = new TextPolishService(current, models, provider, history, states, fidelity, json);
    }
    private Input input(String text) { return new Input(text, null, null, "", "", List.of(), "fixture"); }
    private Generate first(String text) { return new Generate(UUID.randomUUID(), input(text), null, null, null); }
    @Test void defaultsAreNaturalAndLightAndDuplicateRequestUsesOneCall() {
        var request = first("原文很自然。"); var doc = service.generate(request);
        assertThat(doc.input().style()).isEqualTo(Style.NATURAL); assertThat(doc.input().depth()).isEqualTo(Depth.LIGHT);
        assertThat(service.generate(request).id()).isEqualTo(doc.id()); verify(provider, times(1)).polish(any(), any());
        assertThatThrownBy(() -> service.generate(new Generate(request.requestId(), input("不同原文"), null, null, null))).isInstanceOf(TextPolishException.class);
    }
    @Test void originalAndCurrentUseServerSourceInsteadOfClientClaimedTextAndKeepGeneratedSnapshot() {
        var original = service.generate(first("原始文章。"));
        history.edit(a, original.id(), "手动保存后的稿件。", 0);
        var currentResult = service.generate(new Generate(UUID.randomUUID(), input("伪称来源"), original.id(), 1L, SourceMode.CURRENT));
        assertThat(currentResult.input().sourceText()).isEqualTo("手动保存后的稿件。");
        var regenerated = service.generate(new Generate(UUID.randomUUID(), input("伪称来源"), original.id(), 1L, SourceMode.ORIGINAL));
        assertThat(regenerated.input().sourceText()).isEqualTo("原始文章。");
        assertThat(history.find(a, original.id()).generatedText()).isEqualTo("原始文章。");
        assertThatThrownBy(() -> service.generate(new Generate(UUID.randomUUID(), input("内容"), original.id(), 0L, SourceMode.CURRENT))).isInstanceOf(TextPolishException.class);
    }
    @Test void editsConflictAndDeletedHistoryCannotBeRecoveredReSavedOrUsedAsSource() {
        var doc = service.generate(first("文章正文"));
        history.edit(a, doc.id(), "编辑后的正文", 0);
        assertThat(service.operation(doc.id()).document().polishedText()).isEqualTo("编辑后的正文");
        assertThatThrownBy(() -> history.edit(a, doc.id(), "旧标签页", 0)).isInstanceOf(TextPolishException.class);
        history.delete(a, doc.id());
        assertThatThrownBy(() -> service.operation(doc.id())).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> service.save(doc.id())).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> service.generate(new Generate(UUID.randomUUID(), input("正文"), doc.id(), 1L, SourceMode.CURRENT))).isInstanceOf(TextPolishException.class);
        assertThat(history.list(a, 1, 10).total()).isZero();
    }
    @Test void everyIdentityDimensionIsRequiredEvenWithSameUser() {
        var doc = service.generate(first("文章正文"));
        for (var other : List.of(new ActorIdentity(UUID.randomUUID(), a.tenantId(), a.workspaceId(), "other@local", "other"),
                new ActorIdentity(a.userId(), UUID.randomUUID(), a.workspaceId(), "other@local", "other"),
                new ActorIdentity(a.userId(), a.tenantId(), UUID.randomUUID(), "other@local", "other"))) {
            assertThatThrownBy(() -> history.find(other, doc.id())).isInstanceOf(TextPolishException.class);
            when(current.require()).thenReturn(other);
            assertThatThrownBy(() -> service.operation(doc.id())).isInstanceOf(TextPolishException.class);
        }
    }
    @Test void deletionDuringModelWorkCannotCreateContinuation() {
        var source = service.generate(first("原始文章"));
        doAnswer(i -> { history.delete(a, source.id()); return "新正文"; }).when(provider).polish(any(), any());
        assertThatThrownBy(() -> service.generate(new Generate(UUID.randomUUID(), input("正文"), source.id(), 0L, SourceMode.CURRENT))).isInstanceOf(TextPolishException.class);
        assertThat(history.list(a, 1, 10).total()).isZero();
    }
    @Test void lockedTextIsExactAndNumbersOnlyProduceReviewHints() {
        var in = new Input("最多3次；引文：原样。", null, null, "", "", List.of("原样。"), "fixture");
        fidelity.validateInput(in);
        assertThatThrownBy(() -> fidelity.check(in, "最多3次；引文被改。" )).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> fidelity.check(in, "最多3次；引文：原样。原样。" )).isInstanceOf(TextPolishException.class);
        assertThat(fidelity.check(in, "最多4次；引文：原样。")).extracting(Check::code).contains("LOCKS_PRESERVED", "NUMBERS_CHANGED");
        assertThat(fidelity.check(in, "至少3次；引文：原样。")).extracting(Check::code).doesNotContain("SEMANTICS_VERIFIED");
        assertThatThrownBy(() -> fidelity.validateInput(new Input("原文", null,null,"","", List.of("不存在"),""))).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> fidelity.validateInput(input("api_key=fixtureSecretCredential123"))).isInstanceOf(TextPolishException.class);
    }
}
